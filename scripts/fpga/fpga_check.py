#!/usr/bin/env python3
"""Checks the FPGA generator: every test program is run in the interpreter and in the generated hardware
description, simulated with Icarus Verilog (SystemVerilog and Verilog-2001) and GHDL (VHDL), and the results are compared.

usage: fpga_check.py JAR [WORKDIR] [test names...]
needs: java, iverilog, ghdl on the PATH (yosys and verilator are used by check-synth.sh)
"""
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import programs  # noqa: E402

HZ = 1_000_000          # the simulations run the design at 1 MHz so that milliseconds are cheap
BAUD = 115200
# the PWM counter of a 100 MHz board is 16 bits wide (one PWM period = 65536 clock cycles, whatever CLK_HZ the testbench uses)
PWM_WINDOW = 2 * 65536


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def generate(jar, evia, lang, out):
    r = sh(["java", "-jar", jar, "--gen", evia, "--lang", lang, "-o", out])
    if r.returncode != 0:
        raise RuntimeError(f"--gen {lang} failed: {r.stderr.strip()[-400:]}")


def ports_of(sv_text):
    head = sv_text[sv_text.index("module evia_top"):]
    head = head[:head.index(");\n", head.index(") ("))]
    return re.findall(r"^\s*(input|output)\s+wire\s+(\w+)", head, re.M)


# ---------------------------------------------------------------- testbenches

def verilog_tb(ports, run_ms, loopback, duty):
    ins = [n for d, n in ports if d == "input" and n not in ("clk", "uart_rx")]
    outs = [n for d, n in ports if d == "output" and n != "uart_tx"]
    has_tx = any(n == "uart_tx" for _, n in ports)
    has_rx = any(n == "uart_rx" for _, n in ports)
    L = ["`timescale 1ns/1ps", "module tb;",
         f"  parameter HZ = {HZ};", f"  localparam MSC = HZ / 1000;", f"  localparam BD = HZ / {BAUD};",
         "  reg clk = 0;", "  integer cyc = 0;",
         "  always #(500000000.0 / HZ) clk = ~clk;",
         "  always @(posedge clk) cyc <= cyc + 1;"]
    for n in ins:
        L.append(f"  wire {n} = ((cyc / MSC) % 2000) >= 1000;")
    for n in outs:
        L.append(f"  wire {n};")
    if has_tx:
        L.append("  wire uart_tx;")
    if has_rx:
        src = "uart_tx" if loopback and has_tx else "1'b1"
        L.append(f"  wire uart_rx = {src};")
    conns = ["    .clk(clk)"] + [f"    .{n}({n})" for n in ins + outs]
    if has_tx:
        conns.append("    .uart_tx(uart_tx)")
    if has_rx:
        conns.append("    .uart_rx(uart_rx)")
    L.append("  evia_top #(.CLK_HZ(HZ)) dut (\n" + ",\n".join(conns) + "\n  );")
    for n in outs:
        L.append(f"  reg prev_{n} = 0;")
        L.append(f"  always @(posedge clk) if ({n} !== prev_{n}) begin $display(\"EV %0d {n} %0d\", cyc / MSC, {n}); prev_{n} <= {n}; end")
    if has_tx:
        L += ["  integer k; reg [7:0] d;",
              "  initial forever begin",
              "    @(negedge uart_tx);",
              "    repeat (BD + BD / 2) @(posedge clk);",
              "    for (k = 0; k < 8; k = k + 1) begin d[k] = uart_tx; repeat (BD) @(posedge clk); end",
              "    $display(\"UART %0d %0d\", cyc / MSC, d);",
              "  end"]
    if duty:
        L += [f"  localparam WIN = {PWM_WINDOW};", "  integer hi = 0;",
              f"  always @(posedge clk) begin if ({duty}) hi = hi + 1;",
              "    if ((cyc + 1) % WIN == 0) begin $display(\"DUTY %0d %0d\", (cyc + 1) / MSC, hi * 1000 / WIN); hi = 0; end end"]
    L += [f"  initial begin #({run_ms}.0 * 1000000.0); $display(\"END\"); $finish; end", "endmodule"]
    return "\n".join(L) + "\n"


def vhdl_tb(ports, run_ms, loopback, duty):
    ins = [n for d, n in ports if d == "input" and n not in ("clk", "uart_rx")]
    outs = [n for d, n in ports if d == "output" and n != "uart_tx"]
    has_tx = any(n == "uart_tx" for _, n in ports)
    has_rx = any(n == "uart_rx" for _, n in ports)
    sigs = ["clk"] + ins + outs + (["uart_tx"] if has_tx else []) + (["uart_rx"] if has_rx else [])
    L = ["library ieee; use ieee.std_logic_1164.all; use std.textio.all; use std.env.all;",
         "entity tb is end entity;",
         "architecture sim of tb is",
         f"  constant HZ : integer := {HZ};", "  constant MSC : integer := HZ / 1000;", f"  constant BD : integer := HZ / {BAUD};",
         "  signal cyc : integer := 0;"]
    if duty:
        L.append(f"  constant WIN : integer := {PWM_WINDOW};")
    for s in sigs:
        init = "'1'" if s.startswith("uart") else "'0'"
        L.append(f"  signal {s} : std_logic := {init};")
    L += ["  procedure say(s : string) is variable l : line; begin write(l, s); writeline(output, l); end procedure;",
          "  function v(s : std_logic) return string is begin if s = '1' then return \"1\"; else return \"0\"; end if; end function;",
          "begin",
          "  clk <= not clk after (500000000 / HZ) * 1 ns;",
          "  process (clk) begin if rising_edge(clk) then cyc <= cyc + 1; end if; end process;"]
    for n in ins:
        L.append(f"  {n} <= '1' when ((cyc / MSC) mod 2000) >= 1000 else '0';")
    if has_rx:
        L.append(f"  uart_rx <= {'uart_tx' if loopback and has_tx else '1'};")
    pm = ["clk => clk"] + [f"{n} => {n}" for n in ins + outs]
    if has_tx:
        pm.append("uart_tx => uart_tx")
    if has_rx:
        pm.append("uart_rx => uart_rx")
    L.append("  dut : entity work.evia_top generic map (CLK_HZ => HZ) port map (" + ", ".join(pm) + ");")
    for n in outs:
        L.append(f"  mon_{n} : process (clk) variable prev : std_logic := '0'; begin if rising_edge(clk) then "
                 f"if {n} /= prev then say(\"EV \" & integer'image(cyc / MSC) & \" {n} \" & v({n})); prev := {n}; end if; end if; end process;")
    if has_tx:
        L += ["  uart_mon : process variable d : integer; begin",
              "    wait until falling_edge(uart_tx);",
              "    for i in 1 to BD + BD / 2 loop wait until rising_edge(clk); end loop;",
              "    d := 0;",
              "    for k in 0 to 7 loop",
              "      if uart_tx = '1' then d := d + 2 ** k; end if;",
              "      for i in 1 to BD loop wait until rising_edge(clk); end loop;",
              "    end loop;",
              "    say(\"UART \" & integer'image(cyc / MSC) & \" \" & integer'image(d));",
              "  end process;"]
    if duty:
        L += ["  duty_mon : process (clk) variable hi : integer := 0; begin if rising_edge(clk) then",
              f"    if {duty} = '1' then hi := hi + 1; end if;",
              "    if (cyc + 1) mod WIN = 0 then say(\"DUTY \" & integer'image((cyc + 1) / MSC) & \" \" & integer'image(hi * 1000 / WIN)); hi := 0; end if;",
              "  end if; end process;"]
    L += [f"  process begin wait for {run_ms} ms; say(\"END\"); finish; end process;", "end architecture;"]
    return "\n".join(L) + "\n"


# ---------------------------------------------------------------- running

def simulate(lang, d, name, run_ms, loopback, duty):
    sv = open(f"{d}/{name}.sv").read()
    ports = ports_of(sv)
    if lang in ("sv", "verilog"):
        src = f"{d}/{name}.{'sv' if lang == 'sv' else 'v'}"
        tb = f"{d}/{name}_tb_{lang}.v"
        open(tb, "w").write(verilog_tb(ports, run_ms, loopback, duty))
        exe = f"{d}/{name}_{lang}.vvp"
        r = sh(["iverilog", "-g2012" if lang == "sv" else "-g2001", "-o", exe, src, tb])
        if r.returncode != 0:
            raise RuntimeError("iverilog: " + (r.stderr or r.stdout)[-600:])
        r = sh(["vvp", exe])
        return r.stdout
    src = f"{d}/{name}.vhd"
    tb = f"{d}/{name}_tb.vhd"
    open(tb, "w").write(vhdl_tb(ports, run_ms, loopback, duty))
    wd = f"{d}/ghdl_{name}"
    os.makedirs(wd, exist_ok=True)
    for cmd in (["ghdl", "-a", "--std=08", f"--workdir={wd}", src, tb], ["ghdl", "-e", "--std=08", f"--workdir={wd}", "-o", f"{wd}/tb", "tb"]):
        r = sh(cmd)
        if r.returncode != 0:
            raise RuntimeError("ghdl: " + (r.stderr or r.stdout)[-600:])
    r = sh([f"{wd}/tb", "--ieee-asserts=disable"])
    return r.stdout + ("" if r.returncode == 0 else r.stderr)


def parse_hdl(out):
    events, uart, duty = [], [], []
    for line in out.splitlines():
        p = line.split()
        if not p:
            continue
        if p[0] == "EV":
            events.append((int(p[1]), p[2], int(p[3])))
        elif p[0] == "UART":
            uart.append(int(p[2]))
        elif p[0] == "DUTY":
            duty.append((int(p[1]), int(p[2])))
    return events, bytes(uart).decode("latin-1").replace("\r", ""), duty


def parse_sim(jar, evia):
    r = sh(["java", "-jar", jar, "--run", evia])
    if r.returncode != 0:
        raise RuntimeError("--run failed: " + r.stderr.strip()[-300:])
    last = {}
    events = []
    for line in r.stderr.splitlines():
        m = re.match(r"board \[(\d+) ms\] pin (\d+) (HIGH|LOW)$", line)
        if m and int(m.group(2)) < 16:
            ms, pin, lvl = int(m.group(1)), int(m.group(2)), 1 if m.group(3) == "HIGH" else 0
            if last.get(pin, 0) != lvl:
                events.append((ms, f"LED{pin}", lvl))
            last[pin] = lvl
    return events, r.stdout.replace("\r", "")


def close(a, b, tol):
    ta, tb = re.split(r"(-?\d+)", a), re.split(r"(-?\d+)", b)
    if len(ta) != len(tb):
        return False
    for x, y in zip(ta, tb):
        if x.lstrip("-").isdigit() and y.lstrip("-").isdigit():
            if abs(int(x) - int(y)) > tol:
                return False
        elif x != y:
            return False
    return True


def compare(name, kind, extras, sim, hdl):
    problems = []
    h_events, h_text, h_duty = hdl
    if kind == "pwm":
        want = [0, 64, 128, 255, 255, 0, 191, 255, 0]
        got = [p for _, p in h_duty][:len(want)]
        for i, (w, g) in enumerate(zip(want, got)):
            exp = 1000 if w >= 255 else w * 1000 // 256
            if abs(g - exp) > 40:
                problems.append(f"window {i}: duty {g} per mille, expected about {exp}")
        if len(got) < len(want):
            problems.append(f"only {len(got)} duty windows seen")
        return problems
    if kind == "expected":
        want = extras["expect"]()
        got = [l for l in h_text.split("\n") if l != ""]
        if got != want:
            problems.append(f"printed {got!r}, expected {want!r}")
        return problems
    s_events, s_text = sim
    tol = 1 if extras.get("tolerant") else 0
    sl, hl = s_text.split("\n"), h_text.split("\n")
    if len(sl) != len(hl):
        problems.append(f"{len(hl)} lines printed, the simulator printed {len(sl)}:\n  hdl: {h_text!r}\n  sim: {s_text!r}")
    else:
        for i, (a, b) in enumerate(zip(sl, hl)):
            if not close(a, b, tol):
                problems.append(f"line {i + 1}: hdl {b!r}, simulator {a!r}")
    if len(s_events) != len(h_events):
        problems.append(f"{len(h_events)} pin changes, the simulator has {len(s_events)}:\n  hdl: {h_events}\n  sim: {s_events}")
    else:
        for (t1, n1, v1), (t2, n2, v2) in zip(s_events, h_events):
            if n1 != n2 or v1 != v2 or abs(t1 - t2) > 2:
                problems.append(f"pin change: simulator {t1} ms {n1}={v1}, hdl {t2} ms {n2}={v2}")
    return problems


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    jar = os.path.abspath(sys.argv[1])
    d = os.path.abspath(sys.argv[2]) if len(sys.argv) > 2 else "/tmp/evia-fpga-check"
    only = sys.argv[3:]
    os.makedirs(d, exist_ok=True)
    failed = 0
    for name, (build, kind, run_ms, extras) in programs.TESTS.items():
        if only and name not in only:
            continue
        p = build()
        evia = f"{d}/{name}.evia"
        open(evia, "w").write(p.text())
        try:
            for lang, ext in (("sv", "sv"), ("verilog", "v"), ("vhdl", "vhd")):
                generate(jar, evia, lang, f"{d}/{name}.{ext}")
            sim = parse_sim(jar, evia) if kind == "sim" else None
        except RuntimeError as e:
            print(f"FAIL {name}: {e}")
            failed += 1
            continue
        duty = "LED0" if kind == "pwm" else ""
        for lang, label in (("sv", "SystemVerilog"), ("verilog", "Verilog-2001"), ("vhdl", "VHDL")):
            try:
                out = simulate(lang, d, name, run_ms, extras.get("loopback", False), duty)
                hdl = parse_hdl(out)
                if "END" not in out:
                    raise RuntimeError("simulation did not finish: " + out[-300:])
                problems = compare(name, kind, extras, sim, hdl)
            except RuntimeError as e:
                problems = [str(e)]
            if problems:
                failed += 1
                print(f"FAIL {name} [{label}]")
                for pr in problems:
                    print("   " + pr)
            else:
                print(f"ok   {name} [{label}]")
    print("all checks passed" if not failed else f"{failed} check(s) failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
