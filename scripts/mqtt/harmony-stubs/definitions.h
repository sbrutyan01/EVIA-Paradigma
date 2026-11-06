/* Just enough of an MPLAB X Harmony project's "definitions.h" to compile the generated application on
   this computer: the declarations EVIA Paradigma's own code uses, and nothing else. It lets the checks
   catch our mistakes without Harmony installed; it says nothing about whether the real API matches,
   which only a build against Microchip's own headers can tell. */
#ifndef EVIA_HARMONY_STUBS_DEFINITIONS_H
#define EVIA_HARMONY_STUBS_DEFINITIONS_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define CPU_CLOCK_FREQUENCY 80000000UL
#define PERIPHERAL_CLOCK_FREQUENCY 80000000UL

void SYS_Tasks(void);

#include "registers.h"

/* ---- the part of the TCP/IP stack the MQTT transport uses */
typedef void *TCPIP_NET_HANDLE;
typedef int TCP_SOCKET;
#define INVALID_SOCKET ((TCP_SOCKET) -1)
typedef enum { IP_ADDRESS_TYPE_IPV4 = 1, IP_ADDRESS_TYPE_IPV6 = 2 } IP_ADDRESS_TYPE;
typedef union { uint32_t Val; uint8_t v[4]; } IPV4_ADDR;
typedef union { IPV4_ADDR v4Add; uint8_t v6Add[16]; } IP_MULTI_ADDRESS;
typedef enum { TCPIP_DNS_RES_OK = 0, TCPIP_DNS_RES_PENDING = 1, TCPIP_DNS_RES_NO_ENTRY = 2 } TCPIP_DNS_RESULT;
typedef enum { TCPIP_DNS_TYPE_A = 1, TCPIP_DNS_TYPE_AAAA = 2 } TCPIP_DNS_RESOLVE_TYPE;

TCPIP_NET_HANDLE TCPIP_STACK_NetHandleGet(const char *interface);
TCPIP_NET_HANDLE TCPIP_STACK_IndexToNet(int index);
bool TCPIP_STACK_NetIsLinked(TCPIP_NET_HANDLE net);
uint32_t TCPIP_STACK_NetAddress(TCPIP_NET_HANDLE net);
bool TCPIP_Helper_StringToIPAddress(const char *text, IPV4_ADDR *address);
TCPIP_DNS_RESULT TCPIP_DNS_Resolve(const char *name, TCPIP_DNS_RESOLVE_TYPE type);
TCPIP_DNS_RESULT TCPIP_DNS_IsResolved(const char *name, IP_MULTI_ADDRESS *address, IP_ADDRESS_TYPE type);
TCP_SOCKET TCPIP_TCP_ClientOpen(IP_ADDRESS_TYPE type, uint16_t port, IP_MULTI_ADDRESS *address);
bool TCPIP_TCP_IsConnected(TCP_SOCKET socket);
void TCPIP_TCP_Close(TCP_SOCKET socket);
uint16_t TCPIP_TCP_PutIsReady(TCP_SOCKET socket);
uint16_t TCPIP_TCP_ArrayPut(TCP_SOCKET socket, const uint8_t *data, uint16_t count);
void TCPIP_TCP_Flush(TCP_SOCKET socket);
uint16_t TCPIP_TCP_GetIsReady(TCP_SOCKET socket);
uint16_t TCPIP_TCP_ArrayGet(TCP_SOCKET socket, uint8_t *data, uint16_t count);

#endif
