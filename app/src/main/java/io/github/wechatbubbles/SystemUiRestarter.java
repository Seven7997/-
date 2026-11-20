package io.github.wechatbubbles;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** One bounded root request; no Context, retained Activity, service, or shell input. */
final class SystemUiRestarter {
    private static final long ROOT_TIMEOUT_SECONDS = 15L;
    private static final int EXIT_NO_ROOT = 41;
    private static final int EXIT_NOT_FOUND = 42;
    private static final int EXIT_UNSAFE_PID = 43;
    private static final int EXIT_SIGNAL_FAILED = 44;

    /*
     * Constant command only. Check every pidof result before sending any signal, then
     * recheck cmdline immediately before each TERM to reduce PID reuse risk. If /proc
     * cannot be verified, fail closed. No package/user text is interpolated here.
     */
    private static final String RESTART_COMMAND =
            "exec 2>/dev/null\n"
            + "uid=$(/system/bin/id -u) || exit 41\n"
            + "[ \"$uid\" = 0 ] || exit 41\n"
            + "pids=$(/system/bin/pidof com.android.systemui) || exit 42\n"
            + "[ -n \"$pids\" ] || exit 42\n"
            + "set -f\n"
            + "for pid in $pids; do\n"
            + "  case \"$pid\" in ''|*[!0-9]*|0|1) exit 43 ;; esac\n"
            + "  [ \"${#pid}\" -le 10 ] || exit 43\n"
            + "  [ \"$pid\" -gt 1 ] || exit 43\n"
            + "  [ -r \"/proc/$pid/cmdline\" ] || exit 43\n"
            + "  name=$(/system/bin/tr '\\000' '\\n' < \"/proc/$pid/cmdline\") || exit 43\n"
            + "  [ \"$name\" = com.android.systemui ] || exit 43\n"
            + "done\n"
            + "for pid in $pids; do\n"
            + "  name=$(/system/bin/tr '\\000' '\\n' < \"/proc/$pid/cmdline\") || exit 43\n"
            + "  [ \"$name\" = com.android.systemui ] || exit 43\n"
            + "  /system/bin/kill -TERM \"$pid\" || exit 44\n"
            + "done\n"
            + "exit 0";

    enum Result {
        SUCCESS("已请求重新加载系统界面，状态栏等会自动恢复。请稍后刷新模块状态。"),
        ROOT_UNAVAILABLE("重启失败：未取得 Root 授权，设备可能未 Root 或已拒绝授权。"),
        NOT_FOUND("重启失败：未找到系统界面进程，请稍后重试。"),
        UNSAFE_PID("重启失败：无法核对系统界面进程身份，已停止操作。"),
        SIGNAL_FAILED("重启失败：无法向系统界面发送重载信号，请检查 Root 权限。"),
        TIMED_OUT("重启失败：Root 请求超过 15 秒，已终止等待；请检查 Root 授权后重试。"),
        INTERRUPTED("重启操作已中止，请稍后重试。"),
        START_FAILED("重启失败：无法启动 Root 命令，请确认已安装并允许使用 su。"),
        COMMAND_FAILED("重启失败：Root 未获授权或命令执行失败，请检查 Root 管理器。");

        final String message;

        Result(String message) {
            this.message = message;
        }
    }

    private SystemUiRestarter() {}

    /** Must be called on a background thread. Root output is discarded, never logged. */
    static Result restart() {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", RESTART_COMMAND)
                    .redirectOutput(new File("/dev/null"))
                    .redirectError(new File("/dev/null"))
                    .start();
            // su receives no interactive stdin; both output streams bypass Java pipes.
            process.getOutputStream().close();
            if (!process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return Result.TIMED_OUT;
            }
            return resultForExitCode(process.exitValue());
        } catch (IOException | SecurityException exception) {
            return Result.START_FAILED;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Result.INTERRUPTED;
        } catch (RuntimeException exception) {
            return Result.COMMAND_FAILED;
        } finally {
            if (process != null) {
                stopAndClose(process);
            }
        }
    }

    // Kept separate for local checks without starting su or signalling a process.
    static Result resultForExitCode(int exitCode) {
        switch (exitCode) {
            case 0: return Result.SUCCESS;
            case EXIT_NO_ROOT: return Result.ROOT_UNAVAILABLE;
            case EXIT_NOT_FOUND: return Result.NOT_FOUND;
            case EXIT_UNSAFE_PID: return Result.UNSAFE_PID;
            case EXIT_SIGNAL_FAILED: return Result.SIGNAL_FAILED;
            case 1:
            case 126:
            case 127:
            case 255: return Result.ROOT_UNAVAILABLE;
            default: return Result.COMMAND_FAILED;
        }
    }

    private static void stopAndClose(Process process) {
        try {
            if (process.isAlive()) {
                process.destroy();
                // Do not wait again: force termination if the graceful request did not finish it.
                if (process.isAlive()) process.destroyForcibly();
            }
        } catch (RuntimeException ignored) {
            // Cleanup must not suppress the bounded operation's user-visible result.
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // No command output is read or retained.
        }
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
            // Output has already been redirected to /dev/null.
        }
        try {
            process.getErrorStream().close();
        } catch (IOException ignored) {
            // Error output has already been redirected to /dev/null.
        }
    }
}
