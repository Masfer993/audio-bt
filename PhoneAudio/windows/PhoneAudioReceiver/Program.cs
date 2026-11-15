namespace PhoneAudioReceiver;

static class Program
{
    static readonly string LogPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PhoneAudioReceiver", "log.txt");

    public static void Log(string msg)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(LogPath)!);
            File.AppendAllText(LogPath, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {msg}{Environment.NewLine}");
        }
        catch { }
    }

    [STAThread]
    static void Main()
    {
        AppDomain.CurrentDomain.UnhandledException += (_, e) => Log("CRASH: " + e.ExceptionObject);
        try
        {
            using var mutex = new Mutex(true, "PhoneAudioReceiver.SingleInstance", out bool first);
            if (!first) { Log("Уже запущен, выход"); return; }
            Log("Старт");
            ApplicationConfiguration.Initialize();
            Application.ThreadException += (_, e) => Log("UI ERROR: " + e.Exception);
            Application.Run(new TrayContext());
        }
        catch (Exception e)
        {
            Log("FATAL: " + e);
            MessageBox.Show("Ошибка запуска:\n" + e.Message + "\n\nЛог: " + LogPath, "PhoneAudio");
        }
    }
}
