#ifndef AIRLIFT_POSIX_H
#define AIRLIFT_POSIX_H
#define _GNU_SOURCE
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <poll.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/resource.h>
#include <dirent.h>
#include <pthread.h>

/* close_range(2) appeared in Linux 5.9 and CLOSE_RANGE_CLOEXEC in 5.11; glibc wraps it only since 2.34. */
#ifndef SYS_close_range
#define SYS_close_range 436
#endif
#ifndef CLOSE_RANGE_CLOEXEC
#define CLOSE_RANGE_CLOEXEC (1U << 2)
#endif

static inline void airlift_close(int fd) { if (fd >= 0) close(fd); }
/* Keep managed descriptors above stdin/out/err, even if the parent has closed them. */
static inline int airlift_safe_fd(int fd) {
    if(fd<0 || fd>=3) return fd;
    int safe=fcntl(fd,F_DUPFD_CLOEXEC,3); int saved=errno; close(fd); errno=saved; return safe;
}
static inline int airlift_pipe(int fds[2]) {
    if(pipe2(fds,O_CLOEXEC)<0) return -1;
    fds[0]=airlift_safe_fd(fds[0]);
    if(fds[0]<0) { int e=errno; close(fds[1]); fds[1]=-1; errno=e; return -1; }
    fds[1]=airlift_safe_fd(fds[1]);
    if(fds[1]<0) { int e=errno; close(fds[0]); fds[0]=-1; errno=e; return -1; }
    return 0;
}

/* Probe once: first == last == ~0U never names an open descriptor, so a supporting kernel returns 0. */
static int airlift_close_range_state=0; /* 0 unknown, 1 supported, -1 unavailable (old kernel or seccomp) */
static inline int airlift_close_range_supported(void) {
    int state=__atomic_load_n(&airlift_close_range_state,__ATOMIC_RELAXED);
    if(!state) {
        int saved=errno;
        state=syscall(SYS_close_range,~0U,~0U,CLOSE_RANGE_CLOEXEC)==0 ? 1 : -1;
        errno=saved;
        __atomic_store_n(&airlift_close_range_state,state,__ATOMIC_RELAXED);
    }
    return state>0;
}
/* Tests force the fallback path (state -1) on kernels that support close_range; 0 probes again. */
static inline void airlift_set_close_range_state(int state) { __atomic_store_n(&airlift_close_range_state,state,__ATOMIC_RELAXED); }
/* Fallback bound for marking inherited descriptors close-on-exec: the highest open descriptor plus a
 * margin for descriptors that other threads open before the child starts, capped by RLIMIT_NOFILE. */
static inline int airlift_descriptor_limit(void) {
    long cap=65536, highest=-1;
    struct rlimit limit;
    if(getrlimit(RLIMIT_NOFILE,&limit)==0 && limit.rlim_cur!=RLIM_INFINITY && (long)limit.rlim_cur<cap) cap=(long)limit.rlim_cur;
    DIR *dir=opendir("/proc/self/fd");
    if(dir) {
        struct dirent *entry;
        while((entry=readdir(dir))) {
            char *end; long fd=strtol(entry->d_name,&end,10);
            if(end!=entry->d_name && !*end && fd>highest) highest=fd;
        }
        closedir(dir);
    }
    if(highest<0) return (int)cap;
    return (int)(highest+1+1024<cap ? highest+1+1024 : cap);
}

/* Shared with the vfork child, which writes only `error`. */
struct airlift_child {
    const char *exe; char *const *argv; char *const *envp; const char *cwd;
    int fds[3]; int merge; int cloexec_limit;
    volatile int error;
};
static inline __attribute__((noinline, noreturn)) void airlift_child_fail(struct airlift_child *child) {
    child->error = errno ? errno : EIO;
    _exit(127);
}
/* Runs in the vfork child on the parent's memory: async-signal-safe system calls only, no allocation,
 * no Kotlin code, and no return. All signals are blocked on entry. */
static inline __attribute__((noinline, noreturn)) void airlift_child_exec(struct airlift_child *child) {
    struct sigaction action;
    sigset_t empty;
    /* Caught signals would run parent handlers on the shared memory, and SIG_IGN for SIGPIPE is ours. */
    for (int sig = 1; sig < NSIG; sig++) {
        if (sigaction(sig, NULL, &action) < 0) continue;
        if (sig == SIGPIPE || (action.sa_handler != SIG_DFL && action.sa_handler != SIG_IGN)) {
            memset(&action, 0, sizeof(action));
            action.sa_handler = SIG_DFL;
            sigaction(sig, &action, NULL);
        }
    }
    sigemptyset(&empty);
    sigprocmask(SIG_SETMASK, &empty, NULL);
    if (child->cwd && chdir(child->cwd) < 0) airlift_child_fail(child);
    for (int i = 0; i < 3; i++) {
        if (i == 2 && child->merge) { if (dup2(1, 2) < 0) airlift_child_fail(child); continue; }
        if (child->fds[i] >= 0 && dup2(child->fds[i], i) < 0) airlift_child_fail(child);
    }
    /* Descriptors opened elsewhere without O_CLOEXEC (stdio, libraries, the launching shell) stay private.
     * The parent probed close_range; a limit means it is unavailable and every descriptor is marked instead. */
    if (child->cloexec_limit <= 0) syscall(SYS_close_range, 3U, ~0U, CLOSE_RANGE_CLOEXEC);
    else {
        for (int fd = 3; fd < child->cloexec_limit; fd++) {
            int flags = fcntl(fd, F_GETFD);
            if (flags >= 0 && !(flags & FD_CLOEXEC)) fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
        }
    }
    execve(child->exe, child->argv, child->envp);
    airlift_child_fail(child);
}

/* Modes: 0 pipe, 1 inherit, 2 file/truncate, 3 file/append.
 * vfork avoids copying the parent's page tables: the parent thread sleeps until execve or _exit, signals stay
 * blocked across the call, and the child resets handlers and its mask before exec.
 */
static inline int airlift_spawn(const char *exe, char *const argv[], char *const envp[], const char *cwd,
    const int modes[3], const char *const paths[3], int merge, int result[3], int *error) {
    int pipes[3][2] = {{-1,-1},{-1,-1},{-1,-1}}, files[3] = {-1,-1,-1};
    struct airlift_child child;
    sigset_t all, old;
    int pid = -1, saved, rc; *error = 0;
    for (int i=0;i<3;i++) result[i] = -1;
    for (int i=0;i<3;i++) {
        if (i==2 && merge) continue;
        if (modes[i]==0) { if (airlift_pipe(pipes[i])<0) goto failed; }
        else if (modes[i]>=2) {
            files[i] = airlift_safe_fd(open(paths[i], (i==0 ? O_RDONLY : O_WRONLY|O_CREAT|(modes[i]==3 ? O_APPEND : O_TRUNC))|O_CLOEXEC, 0666));
            if (files[i]<0) goto failed;
        }
    }
    memset(&child, 0, sizeof(child));
    child.exe = exe; child.argv = argv; child.envp = envp; child.cwd = cwd; child.merge = merge;
    for (int i=0;i<3;i++) child.fds[i] = modes[i]==0 ? pipes[i][i==0 ? 0 : 1] : files[i];
    child.cloexec_limit = airlift_close_range_supported() ? 0 : airlift_descriptor_limit();
    child.error = 0;
    sigfillset(&all);
    rc = pthread_sigmask(SIG_BLOCK, &all, &old);
    if (rc) { errno = rc; goto failed; }
    pid = vfork();
    if (pid == 0) airlift_child_exec(&child);
    saved = errno;
    pthread_sigmask(SIG_SETMASK, &old, NULL);
    if (pid < 0) { errno = saved; goto failed; }
    for (int i=0;i<3;i++) {
        airlift_close(files[i]); files[i]=-1;
        if (pipes[i][0]>=0) {
            result[i]=pipes[i][i==0 ? 1 : 0];
            close(pipes[i][i==0 ? 0 : 1]);
            pipes[i][0]=pipes[i][1]=-1;
        }
    }
    if (child.error) {
        *error = child.error;
        for(int i=0;i<3;i++) { airlift_close(result[i]); result[i]=-1; }
        while(waitpid(pid,NULL,0)<0 && errno==EINTR) {}
        return -1;
    }
    for(int i=0;i<3;i++) if(result[i]>=0) {
        int flags=fcntl(result[i],F_GETFL);
        if(flags<0 || fcntl(result[i],F_SETFL,flags|O_NONBLOCK)<0) {
            *error=errno; kill(pid,SIGKILL); while(waitpid(pid,NULL,0)<0 && errno==EINTR) {}
            for(int k=0;k<3;k++) { airlift_close(result[k]); result[k]=-1; }
            return -1;
        }
    }
    return pid;
failed:
    *error=errno;
    for(int i=0;i<3;i++) { airlift_close(pipes[i][0]); airlift_close(pipes[i][1]); airlift_close(files[i]); }
    return -1;
}
/* Library callers may not ignore SIGPIPE: block it on this thread and consume our own newly generated signal.
 * The rsync-airlift executable also ignores SIGPIPE process-wide; children get SIG_DFL back before exec. */
static inline ssize_t airlift_write(int fd, const void *data, size_t size) {
    sigset_t set, old, pending; sigemptyset(&set); sigaddset(&set,SIGPIPE);
    int rc=pthread_sigmask(SIG_BLOCK,&set,&old); if(rc) { errno=rc; return -1; }
    sigpending(&pending); int already=sigismember(&pending,SIGPIPE);
    ssize_t n=write(fd,data,size); int saved=errno;
    if(n<0 && saved==EPIPE && !already) { struct timespec zero={0,0}; while(sigtimedwait(&set,NULL,&zero)<0 && errno==EINTR) {} }
    pthread_sigmask(SIG_SETMASK,&old,NULL); errno=saved; return n;
}
static inline int airlift_remove_contents(int fd) {
    DIR *dir=fdopendir(fd); if(!dir) { int e=errno; close(fd); errno=e; return -1; }
    struct dirent *entry; int error=0;
    for(;;) {
        errno=0; entry=readdir(dir); if(!entry) { if(errno) error=errno; break; }
        if(!strcmp(entry->d_name,".") || !strcmp(entry->d_name,"..")) continue;
        struct stat st;
        if(fstatat(dirfd(dir),entry->d_name,&st,AT_SYMLINK_NOFOLLOW)<0) { error=errno; break; }
        if(S_ISDIR(st.st_mode)) {
            int child=openat(dirfd(dir),entry->d_name,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
            if(child<0 || airlift_remove_contents(child)<0 || unlinkat(dirfd(dir),entry->d_name,AT_REMOVEDIR)<0) { error=errno; break; }
        } else if(unlinkat(dirfd(dir),entry->d_name,0)<0) { error=errno; break; }
    }
    closedir(dir); if(error) { errno=error; return -1; } return 0;
}
static inline int airlift_remove_directory(const char *path) {
    int fd=open(path,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    if(fd<0) return errno==ENOENT ? 0 : -1;
    if(airlift_remove_contents(fd)<0) return -1;
    return rmdir(path);
}
static inline char **airlift_environ(void) { extern char **environ; return environ; }
/* Wakes a poll() loop; the pipe is non-blocking, so a full pipe already means a pending wakeup. */
static inline void airlift_wake(int fd) { char byte=1; int saved=errno; while(write(fd,&byte,1)<0 && errno==EINTR) {} errno=saved; }
static inline void airlift_drain(int fd) { char bytes[256]; int saved=errno; while(read(fd,bytes,sizeof(bytes))>0) {} errno=saved; }
static inline int airlift_nonblocking(int fd) { int flags=fcntl(fd,F_GETFL); return flags<0 ? -1 : fcntl(fd,F_SETFL,flags|O_NONBLOCK); }
#endif
