using Windows.Devices.Enumeration;
using Windows.Media.Audio;
namespace PhoneAudioReceiver;

/// Делает ПК Bluetooth-колонкой для телефона (Windows AudioPlaybackConnection = A2DP Sink).
/// Open: ПК подключает A2DP к телефону, Android сам направляет звук на ПК.
/// Close: соединение закрывается, Android возвращает звук на свой динамик. Сопряжение сохраняется.
class AudioBridge : IDisposable
{
    AudioPlaybackConnection? _conn;
    public bool IsOpen { get; private set; }
    public event Action<bool>? StateChanged;

    void Set(bool v) { if (IsOpen == v) return; IsOpen = v; StateChanged?.Invoke(v); }

    public async Task<string> OpenAsync(string? addrHex)
    {
        await CloseAsync();
        try
        {
            var id = await FindIdAsync(addrHex);
            if (id == null) return "ERR phone-not-found-in-paired-devices";
            var c = AudioPlaybackConnection.TryCreateFromId(id);
            if (c == null) return "ERR cannot-create-connection";
            c.StateChanged += (s, _) => Set(s.State == AudioPlaybackConnectionState.Opened);
            await c.StartAsync();
            var r = await c.OpenAsync();
            if (r.Status != AudioPlaybackConnectionOpenResultStatus.Success)
            {
                c.Dispose();
                return "ERR " + r.Status;
            }
            _conn = c; Set(true);
            return "OK";
        }
        catch (Exception e)
        {
            Program.Log("OpenAsync: " + e);
            return "ERR " + e.Message.Replace('\n', ' ');
        }
    }

    public Task CloseAsync()
    {
        var c = _conn; _conn = null;
        try { c?.Dispose(); } catch (Exception e) { Program.Log("Close: " + e.Message); }
        Set(false);
        return Task.CompletedTask;
    }

    static async Task<string?> FindIdAsync(string? addrHex)
    {
        var devs = await DeviceInformation.FindAllAsync(AudioPlaybackConnection.GetDeviceSelector());
        foreach (var d in devs) Program.Log($"A2DP-кандидат: {d.Name} | {d.Id}");
        if (addrHex != null)
        {
            var m = devs.FirstOrDefault(d => d.Id.Replace(":", "").Contains(addrHex, StringComparison.OrdinalIgnoreCase));
            if (m != null) return m.Id;
        }
        return devs.Count == 1 ? devs[0].Id : null;
    }

    public void Dispose() => CloseAsync();
}
