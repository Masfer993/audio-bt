using System.Drawing;
namespace PhoneAudioReceiver;

class TrayContext : ApplicationContext
{
    readonly AudioBridge _audio = new();
    readonly BtServer _bt;
    readonly NotifyIcon _tray = new(); readonly System.Windows.Forms.Timer _timer = new() { Interval = 1000 };
    readonly Icon _idle = MakeIcon(Color.Gray), _link = MakeIcon(Color.Orange), _play = MakeIcon(Color.LimeGreen);
    readonly ToolStripMenuItem _status = new() { Enabled = false };
    int _ticks;

    public TrayContext()
    {
        _bt = new BtServer(_audio);

        var menu = new ContextMenuStrip();
        menu.Items.Add(_status);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Вернуть звук на телефон", null, async (_, _) => await _audio.CloseAsync());
        var auto = new ToolStripMenuItem("Запускать с Windows") { Checked = Settings.AutostartEnabled, CheckOnClick = true };
        auto.Click += (_, _) => Settings.SetAutostart(auto.Checked);
        menu.Items.Add(auto);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Выход", null, (_, _) => ExitThread());

        _tray.ContextMenuStrip = menu; _tray.Visible = true;
        _timer.Tick += async (_, _) =>
        {
            if (!_bt.Running && _ticks++ % 10 == 0) await _bt.TryStartAsync();   // Bluetooth выключен — повторяем
            Refresh();
        };
        _timer.Start();
        _ = _bt.TryStartAsync();
        Refresh();
    }

    void Refresh()
    {
        string text; Icon icon;
        if (!_bt.Running) { text = "Bluetooth недоступен, жду…"; icon = _idle; }
        else if (_audio.IsOpen) { text = $"Звук идёт с {_bt.PhoneName}"; icon = _play; }
        else if (_bt.Connected) { text = $"{_bt.PhoneName}: на связи"; icon = _link; }
        else { text = "Ожидание телефона…"; icon = _idle; }
        _status.Text = text; _tray.Icon = icon;
        _tray.Text = text.Length > 63 ? text[..63] : text;
    }

    static Icon MakeIcon(Color c)
    {
        using var bmp = new Bitmap(16, 16);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            using var b = new SolidBrush(c); g.FillEllipse(b, 1, 1, 14, 14);
        }
        return Icon.FromHandle(bmp.GetHicon());
    }

    protected override void ExitThreadCore()
    {
        _timer.Stop(); _tray.Visible = false; _tray.Dispose();
        _bt.Dispose(); _audio.Dispose();
        base.ExitThreadCore();
    }
}
