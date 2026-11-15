using System.Text;
using System.Text.RegularExpressions;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;
namespace PhoneAudioReceiver;

/// Bluetooth RFCOMM сервер команд. Телефон держит соединение открытым и шлёт OPEN/CLOSE.
/// Пропало соединение с телефоном: звук автоматически возвращается на телефон.
class BtServer : IDisposable
{
    readonly AudioBridge _audio;
    RfcommServiceProvider? _provider; StreamSocketListener? _listener;
    StreamSocket? _active; Func<string, Task>? _send;
    public bool Running { get; private set; }
    public bool Connected { get; private set; }
    public string? PhoneName { get; private set; }
    public event Action? Changed;

    public BtServer(AudioBridge audio)
    {
        _audio = audio;
        _audio.StateChanged += open =>
        {
            var s = _send;
            if (s != null) _ = s("STATE " + (open ? "open" : "closed"));
            Changed?.Invoke();
        };
    }

    public async Task<bool> TryStartAsync()
    {
        Cleanup();
        try
        {
            _provider = await RfcommServiceProvider.CreateAsync(RfcommServiceId.FromUuid(Proto.ServiceUuid));
            _listener = new StreamSocketListener();
            _listener.ConnectionReceived += (_, a) => _ = HandleAsync(a.Socket);
            await _listener.BindServiceNameAsync(_provider.ServiceId.AsString(),
                SocketProtectionLevel.BluetoothEncryptionAllowNullAuthentication);
            _provider.StartAdvertising(_listener, true);
            Running = true;
        }
        catch (Exception e) { Program.Log("BT start: " + e); Running = false; }
        return Running;
    }

    void Cleanup()
    {
        Running = false;
        try { _provider?.StopAdvertising(); } catch { }
        try { _listener?.Dispose(); } catch { }
        _provider = null; _listener = null;
    }

    async Task HandleAsync(StreamSocket sock)
    {
        var old = _active; _active = sock;
        try { old?.Dispose(); } catch { }
        string? addr = HexAddr(sock.Information.RemoteHostName?.CanonicalName);
        Program.Log("RFCOMM подключение, адрес=" + addr);

        var reader = new DataReader(sock.InputStream) { InputStreamOptions = InputStreamOptions.Partial };
        var writer = new DataWriter(sock.OutputStream);
        var wlock = new SemaphoreSlim(1, 1);
        async Task Send(string line)
        {
            await wlock.WaitAsync();
            try { writer.WriteString(line + "\n"); await writer.StoreAsync(); }
            catch { }
            finally { wlock.Release(); }
        }
        _send = Send;

        var sb = new StringBuilder();
        try
        {
            while (true)
            {
                var load = reader.LoadAsync(256).AsTask();
                _ = load.ContinueWith(t => t.Exception, TaskContinuationOptions.OnlyOnFaulted);
                if (await Task.WhenAny(load, Task.Delay(12000)) != load) break;   // телефон молчит >12 с
                uint n = await load;
                if (n == 0) break;
                var bytes = new byte[n]; reader.ReadBytes(bytes);
                sb.Append(Encoding.UTF8.GetString(bytes));
                int i;
                while ((i = sb.ToString().IndexOf('\n')) >= 0)
                {
                    var line = sb.ToString(0, i).Trim(); sb.Remove(0, i + 1);
                    if (line.Length > 0) await Process(line, addr, Send);
                }
            }
        }
        catch (Exception e) { Program.Log("RFCOMM: " + e.Message); }
        finally
        {
            if (ReferenceEquals(_active, sock))
            {
                _active = null; _send = null; Connected = false; PhoneName = null;
                await _audio.CloseAsync();   // звук обратно на телефон
                Changed?.Invoke();
            }
            try { sock.Dispose(); } catch { }
        }
    }

    async Task Process(string line, string? addr, Func<string, Task> send)
    {
        var p = line.Split(' ', 2);
        string reply = "ERR unknown-command";
        switch (p[0])
        {
            case "HELLO":
                PhoneName = p.Length > 1 ? p[1].Replace('_', ' ') : "Телефон"; Connected = true;
                reply = "OK " + (_audio.IsOpen ? "open" : "closed"); Changed?.Invoke(); break;
            case "PING": reply = "PONG"; break;
            case "OPEN": reply = await _audio.OpenAsync(addr); break;
            case "CLOSE": await _audio.CloseAsync(); reply = "OK"; break;
        }
        await send(reply);
    }

    static string? HexAddr(string? name)
    {
        if (name == null) return null;
        var hex = Regex.Replace(name, "[^0-9A-Fa-f]", "");
        return hex.Length == 12 ? hex : null;
    }

    public void Dispose() => Cleanup();
}
