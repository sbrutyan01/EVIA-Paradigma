/* Runs the generated MQTT client against a real broker on this computer: the chip's network
   functions (rt_net_join, rt_net_connect, rt_net_write, rt_net_read) become an ordinary socket.
   Usage: harness <port>; prints what MQTT Read gives back. */
#define _POSIX_C_SOURCE 200809L
#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <poll.h>
#include <time.h>
#include <string.h>
#include <unistd.h>

#define RT_MQTT_CLIENT "evia-paradigma"

static int rt_sock = -1;
static int rt_port = 1883;

static int rt_net_join(const char *network, const char *password) {
    (void) network;
    (void) password;
    return 1;
}

static int rt_net_connect(const char *host, int port) {
    struct sockaddr_in a;
    (void) host;
    (void) port;
    if (rt_sock >= 0) close(rt_sock);
    rt_sock = socket(AF_INET, SOCK_STREAM, 0);
    if (rt_sock < 0) return 0;
    memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_port = htons((unsigned short) rt_port);
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    return connect(rt_sock, (struct sockaddr *) &a, sizeof a) == 0;
}

static int rt_net_write(const unsigned char *p, int n) {
    int done = 0;
    while (done < n) {
        ssize_t w = write(rt_sock, p + done, (size_t) (n - done));
        if (w <= 0) return done;
        done += (int) w;
    }
    return done;
}

static int rt_net_read(unsigned char *p, int n, unsigned long ms) {
    int got = 0;
    while (got < n) {
        struct pollfd f = {rt_sock, POLLIN, 0};
        if (poll(&f, 1, (int) ms) <= 0) break;
        ssize_t r = read(rt_sock, p + got, (size_t) (n - got));
        if (r <= 0) break;
        got += (int) r;
    }
    return got;
}

#include "core.c"

int main(int argc, char **argv) {
    double v;
    int i;
    if (argc > 1) rt_port = atoi(argv[1]);
    if (!mqttConnect("network", "password", "127.0.0.1", rt_port)) {
        printf("connect failed\n");
        return 1;
    }
    printf("connected\n");
    mqttSubscribe("evia/command");
    mqttPublish("evia/greeting", "hello");
    mqttPublishNum("evia/temperature", 21.5);
    mqttPublishNum("evia/count", -7);
    for (i = 0; i < 40; i++) {                 /* the broker answers with two messages */
        if (mqttHas("evia/command")) {
            char buf[32];
            v = mqttRead("evia/command");
            rt_fmt(v, buf);
            printf("command %s\n", buf);
        }
        nanosleep(&(struct timespec) {0, 50000000L}, NULL);
    }
    printf("missing %g\n", mqttRead("evia/other"));
    return 0;
}
