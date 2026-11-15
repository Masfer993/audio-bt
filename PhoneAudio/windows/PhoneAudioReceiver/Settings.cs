using Microsoft.Win32;
namespace PhoneAudioReceiver;

static class Settings
{
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    public static bool AutostartEnabled
    {
        get { using var k = Registry.CurrentUser.OpenSubKey(RunKey); return k?.GetValue("PhoneAudioReceiver") != null; }
    }

    public static void SetAutostart(bool on)
    {
        using var k = Registry.CurrentUser.OpenSubKey(RunKey, true)!;
        if (on) k.SetValue("PhoneAudioReceiver", $"\"{Environment.ProcessPath}\"");
        else k.DeleteValue("PhoneAudioReceiver", false);
    }
}
