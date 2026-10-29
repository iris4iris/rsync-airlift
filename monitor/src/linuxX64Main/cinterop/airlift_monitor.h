#ifndef AIRLIFT_MONITOR_H
#define AIRLIFT_MONITOR_H
#define _GNU_SOURCE
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <poll.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

static inline void airlift_clear_errno(void) { errno=0; }
static inline int airlift_listen(int *port) {
    int fd=socket(AF_INET,SOCK_STREAM|SOCK_CLOEXEC|SOCK_NONBLOCK,0);
    if(fd<0) return -1;
    struct sockaddr_in address; memset(&address,0,sizeof(address));
    address.sin_family=AF_INET; address.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    socklen_t size=sizeof(address);
    if(bind(fd,(struct sockaddr*)&address,sizeof(address))<0 || listen(fd,16)<0 || getsockname(fd,(struct sockaddr*)&address,&size)<0) {
        int error=errno; close(fd); errno=error; return -1;
    }
    *port=ntohs(address.sin_port); return fd;
}
static inline int airlift_accept(int fd) { return accept4(fd,NULL,NULL,SOCK_CLOEXEC|SOCK_NONBLOCK); }
/* accept(2): Linux reports pending network errors and resource exhaustion through accept; neither
 * means the listener is unusable. 1 retry now, 2 retry after a pause, 0 fatal. */
static inline int airlift_accept_retry(int error) {
    switch(error) {
        case EINTR: case ECONNABORTED: case EPROTO: case ENETDOWN: case ENOPROTOOPT: case EHOSTDOWN:
        case ENONET: case EHOSTUNREACH: case EOPNOTSUPP: case ENETUNREACH: return 1;
        case EMFILE: case ENFILE: case ENOBUFS: case ENOMEM: return 2;
        default: return 0;
    }
}
static inline int airlift_connect(int port) {
    int fd=socket(AF_INET,SOCK_STREAM|SOCK_CLOEXEC|SOCK_NONBLOCK,0);
    if(fd<0) return -1;
    struct sockaddr_in address; memset(&address,0,sizeof(address));
    address.sin_family=AF_INET; address.sin_port=htons(port); address.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    if(connect(fd,(struct sockaddr*)&address,sizeof(address))<0 && errno!=EINPROGRESS) { int error=errno; close(fd); errno=error; return -1; }
    return fd;
}
static inline int airlift_connected(int fd) {
    struct pollfd item={fd,POLLOUT,0}; int result=poll(&item,1,0);
    if(result<=0) return result;
    int error=0; socklen_t size=sizeof(error);
    if(getsockopt(fd,SOL_SOCKET,SO_ERROR,&error,&size)<0) return -1;
    if(error) { errno=error; return -1; } return 1;
}
static inline ssize_t airlift_send(int fd,const void *bytes,size_t count) { return send(fd,bytes,count,MSG_NOSIGNAL); }
/* Waits up to timeout milliseconds; returns poll's result and keeps EINTR visible to the caller. */
static inline int airlift_wait_fd(int fd,int events,int timeout) { struct pollfd item={fd,(short)events,0}; return poll(&item,1,timeout); }

/* The executable ignores SIGPIPE: closed stdout/stderr pipes and sockets surface as EPIPE.
 * Spawned children get SIG_DFL back before exec (see airlift_posix.h). */
static inline int airlift_ignore_sigpipe(void) {
    struct sigaction action; memset(&action,0,sizeof(action)); sigemptyset(&action.sa_mask); action.sa_handler=SIG_IGN;
    return sigaction(SIGPIPE,&action,NULL);
}

static volatile sig_atomic_t airlift_stop_requested=0;
static int airlift_signal_users=0;
static void airlift_signal_handler(int signal_number) { airlift_stop_requested=signal_number; }
/* SIGHUP stays ignored when the launcher ignored it (nohup), as on the JVM. */
struct airlift_signals { struct sigaction interrupt, terminate, hangup; int hangup_installed; };
static inline struct airlift_signals *airlift_install_signals(void) {
    struct airlift_signals *state=malloc(sizeof(*state)); if(!state) return NULL;
    memset(state,0,sizeof(*state));
    struct sigaction action; memset(&action,0,sizeof(action)); sigemptyset(&action.sa_mask);
    action.sa_handler=airlift_signal_handler; action.sa_flags=SA_RESTART;
    if(!airlift_signal_users) airlift_stop_requested=0;
    if(sigaction(SIGINT,&action,&state->interrupt)<0) { free(state); return NULL; }
    if(sigaction(SIGTERM,&action,&state->terminate)<0) { sigaction(SIGINT,&state->interrupt,NULL); free(state); return NULL; }
    struct sigaction current;
    if(sigaction(SIGHUP,NULL,&current)==0 && current.sa_handler!=SIG_IGN) {
        if(sigaction(SIGHUP,&action,&state->hangup)<0) {
            sigaction(SIGTERM,&state->terminate,NULL); sigaction(SIGINT,&state->interrupt,NULL); free(state); return NULL;
        }
        state->hangup_installed=1;
    }
    airlift_signal_users++; return state;
}
static inline int airlift_stopping(void) { return airlift_stop_requested!=0; }
static inline int airlift_stop_signal(void) { return airlift_stop_requested; }
static inline void airlift_restore_signals(struct airlift_signals *state) {
    if(state->hangup_installed) sigaction(SIGHUP,&state->hangup,NULL);
    sigaction(SIGINT,&state->interrupt,NULL); sigaction(SIGTERM,&state->terminate,NULL); if(airlift_signal_users>0) airlift_signal_users--; free(state);
}
static inline int airlift_signal_ignored(int signal_number) {
    struct sigaction current; return sigaction(signal_number,NULL,&current)==0 && current.sa_handler==SIG_IGN;
}

#include <termios.h>
#include <sys/ioctl.h>
#include <wchar.h>
#include <locale.h>
struct airlift_terminal { int fd; struct termios saved; };
/* Opens the controlling terminal (or a given device for tests) in raw, non-blocking mode. */
static inline struct airlift_terminal *airlift_terminal_open_path(const char *path) {
    struct airlift_terminal *state=malloc(sizeof(*state)); if(!state) return NULL;
    state->fd=open(path,O_RDWR|O_CLOEXEC|O_NONBLOCK|O_NOCTTY);
    if(state->fd<0) { int e=errno; free(state); errno=e; return NULL; }
    if(tcgetattr(state->fd,&state->saved)<0) { int e=errno; close(state->fd); free(state); errno=e; return NULL; }
    struct termios raw=state->saved; cfmakeraw(&raw); raw.c_cc[VMIN]=1; raw.c_cc[VTIME]=0;
    if(tcsetattr(state->fd,TCSANOW,&raw)<0) { int e=errno; close(state->fd); free(state); errno=e; return NULL; }
    setlocale(LC_CTYPE,""); return state;
}
static inline struct airlift_terminal *airlift_terminal_open(void) { return airlift_terminal_open_path("/dev/tty"); }
static inline void airlift_terminal_close(struct airlift_terminal *state) {
    while(tcsetattr(state->fd,TCSANOW,&state->saved)<0 && errno==EINTR) {} close(state->fd); free(state);
}
static inline int airlift_terminal_size(int fd,int *columns,int *rows) {
    struct winsize size;
    if(ioctl(fd,TIOCGWINSZ,&size)<0 || !size.ws_col || !size.ws_row) return -1;
    *columns=size.ws_col; *rows=size.ws_row; return 0;
}
static inline int airlift_column_width(unsigned int code) { return wcwidth((wchar_t)code); }
static inline void airlift_request_stop(void) { airlift_stop_requested=SIGINT; }
#endif
