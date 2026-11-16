package app.framex.core.shizuku;

interface IShellService {
    // Reserved destroy transaction required by Shizuku user services.
    void destroy() = 16777114;

    // Runs one already-validated command in the shell process; returns "<exit code>\n<output>".
    String exec(String command) = 1;
}
