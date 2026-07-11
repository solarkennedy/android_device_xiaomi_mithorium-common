/* qrtr-services.c — list QMI services currently registered on the QRTR bus.
 *
 * Sends a single QRTR_TYPE_NEW_LOOKUP(service=0,instance=0) to the control
 * port and prints every QRTR_TYPE_NEW_SERVER reply qrtr-ns streams back — i.e.
 * a flat directory of every QMI service the modem/ADSP currently advertises.
 *
 * This is the Cluster A measurement instrument (see PLAN-qrtr.md): the
 * load-bearing question is whether QMI_LOC (service 16 / 0x10) is on the bus.
 *
 * Build (in-tree):  mm  (cc_binary "qrtr-services", see Android.bp)
 * Run:              adb shell /vendor/bin/qrtr-services
 *
 * NOTE: the control-packet command IDs are taken from <linux/qrtr.h> rather
 * than hardcoded — on this kernel NEW_SERVER=4 and NEW_LOOKUP=10 (older notes
 * used 3/9, which are BYE and PING; using those silently returns nothing).
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <errno.h>
#include <unistd.h>
#include <endian.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <linux/qrtr.h>            /* sockaddr_qrtr, qrtr_ctrl_pkt, QRTR_TYPE_*, QRTR_PORT_CTRL */

#ifndef AF_QIPCRTR
#define AF_QIPCRTR 42
#endif
#ifndef QRTR_PORT_CTRL
#define QRTR_PORT_CTRL 0xfffffffeu
#endif
/* Authoritative values come from <linux/qrtr.h>; guard only for old headers. */
#ifndef QRTR_TYPE_NEW_SERVER
#define QRTR_TYPE_NEW_SERVER 4
#endif
#ifndef QRTR_TYPE_NEW_LOOKUP
#define QRTR_TYPE_NEW_LOOKUP 10
#endif

/* Fallback definitions if the platform header is too old. */
#ifndef _LINUX_QRTR_H
struct sockaddr_qrtr { unsigned short sq_family; uint32_t sq_node; uint32_t sq_port; };
struct qrtr_ctrl_pkt {
    uint32_t cmd;
    union {
        struct { uint32_t service, instance, node, port; } server;
        struct { uint32_t node, port; } client;
    };
} __attribute__((packed));
#endif

static const char *svc_name(uint32_t s)
{
    switch (s) {
    case 1:   return "WDS";
    case 2:   return "DMS";
    case 3:   return "NAS";
    case 5:   return "WMS";
    case 6:   return "PDS";
    case 9:   return "VOICE";
    case 11:  return "UIM";
    case 12:  return "PBM";
    case 14:  return "RMTFS";
    case 16:  return "LOC";        /* <-- the service we are hunting */
    case 26:  return "WDA";
    case 256: return "SENSOR/SMGR";
    default:  return "?";
    }
}

int main(void)
{
    int fd = socket(AF_QIPCRTR, SOCK_DGRAM, 0);
    if (fd < 0) { perror("socket(AF_QIPCRTR)"); return 1; }

    struct sockaddr_qrtr sq;
    socklen_t sl = sizeof(sq);
    if (getsockname(fd, (void *)&sq, &sl) < 0) { perror("getsockname"); return 1; }
    fprintf(stderr, "local qrtr node = %u\n", sq.sq_node);

    struct qrtr_ctrl_pkt pkt;
    memset(&pkt, 0, sizeof(pkt));
    pkt.cmd = htole32(QRTR_TYPE_NEW_LOOKUP);     /* service=0,instance=0 => ALL */

    struct sockaddr_qrtr ctrl = {
        .sq_family = AF_QIPCRTR,
        .sq_node   = sq.sq_node,
        .sq_port   = QRTR_PORT_CTRL,
    };
    if (sendto(fd, &pkt, sizeof(pkt), 0, (void *)&ctrl, sizeof(ctrl)) < 0) {
        perror("sendto(NEW_LOOKUP)"); return 1;
    }

    struct timeval tv = { .tv_sec = 3, .tv_usec = 0 };   /* drain until quiet */
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    printf("%-4s %-12s %-10s %-6s %-6s\n", "SVC", "NAME", "INST(v)", "NODE", "PORT");
    int count = 0;
    for (;;) {
        struct qrtr_ctrl_pkt r;
        ssize_t n = recv(fd, &r, sizeof(r), 0);
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) break;
            perror("recv"); break;
        }
        if ((size_t)n < sizeof(uint32_t)) continue;
        if (le32toh(r.cmd) != QRTR_TYPE_NEW_SERVER) continue;
        uint32_t svc  = le32toh(r.server.service);
        uint32_t inst = le32toh(r.server.instance);
        uint32_t node = le32toh(r.server.node);
        uint32_t port = le32toh(r.server.port);
        if (!svc && !node && !port) continue;            /* sentinel */
        printf("%-4u %-12s 0x%08x %-6u %-6u\n", svc, svc_name(svc), inst, node, port);
        count++;
    }
    fprintf(stderr, "%d services registered\n", count);
    close(fd);
    return 0;
}
