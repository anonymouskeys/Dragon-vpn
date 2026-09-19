using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using System.Text.Json.Nodes;
using DragonVPN.Models;

namespace DragonVPN.Services;

/// <summary>
/// Windows equivalent of CoreVpnService: Xray exposes the local SOCKS endpoint and
/// hev-socks5-tunnel creates the system-wide Wintun adapter. v2rayN is deliberately
/// not used; DragonVPN owns the complete UI and process lifecycle.
/// </summary>
public sealed class DragonRuntime : IDisposable
{
    private readonly SettingsStore _store;
    private Process? _xray;
    private Process? _tunnel;
    private Process? _byeDpi;
    private int? _tunIndex;
    private readonly List<string> _bypassAddresses = [];

    public event Action<string>? Log;
    public bool IsRunning => _xray is { HasExited: false } && _tunnel is { HasExited: false };

    public DragonRuntime(SettingsStore store) => _store = store;

    public async Task StartAsync(DragonProfile profile, DragonSettings settings)
    {
        Stop();
        if (settings.ByeDpiEnabled) await StartByeDpiAsync(settings);

        var configPath = Path.Combine(_store.RuntimeDirectory, "dragon-xray.json");
        var config = BuildConfig(profile, settings);
        await File.WriteAllTextAsync(configPath, config.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));

        var xrayPath = Path.Combine(AppContext.BaseDirectory, "bin", "xray.exe");
        if (!File.Exists(xrayPath)) throw new FileNotFoundException("Не найдено сетевое ядро bin\\xray.exe", xrayPath);
        _xray = StartProcess(xrayPath, $"run -c \"{configPath}\"", "Xray");
        await WaitForPortAsync(_xray, 10808, "Xray не открыл локальный порт 10808.");

        var tunnelConfig = Path.Combine(_store.RuntimeDirectory, "dragon-tunnel.yml");
        await File.WriteAllTextAsync(tunnelConfig, BuildTunnelConfig());
        var tunnelPath = Path.Combine(AppContext.BaseDirectory, "bin", "hev-socks5-tunnel.exe");
        if (!File.Exists(tunnelPath)) throw new FileNotFoundException("Не найден bin\\hev-socks5-tunnel.exe", tunnelPath);
        _tunnel = StartProcess(tunnelPath, $"\"{tunnelConfig}\"", "TUN");

        _tunIndex = await WaitForTunnelAsync();
        await InstallRoutesAsync(profile, _tunIndex.Value);
        Log?.Invoke("DragonVPN подключён. Системный TUN активен.");
    }

    public void Stop()
    {
        RemoveRoutes();
        StopProcess(ref _tunnel, "TUN");
        StopProcess(ref _xray, "Xray");
        StopProcess(ref _byeDpi, "ByeDPI");
    }

    private async Task StartByeDpiAsync(DragonSettings settings)
    {
        var path = Path.Combine(AppContext.BaseDirectory, "bin", "byedpi", "ciadpi.exe");
        if (!File.Exists(path)) throw new FileNotFoundException("Не найден официальный ByeDPI", path);
        var args = new List<string>
        {
            "--ip", "127.0.0.1", "--port", "10809", "--max-conn", "1024", "--timeout", "3",
            "--cache-ttl", "86400", "--auto-mode", "3", "--proto", "http,tls"
        };
        if (settings.ByeDpiPortsOnly) args.AddRange(["--pf", "80-443"]);
        args.AddRange(ByeDpiPreset(settings));
        if (!string.IsNullOrWhiteSpace(settings.ByeDpiExpertArgs)) args.AddRange(SplitArgs(settings.ByeDpiExpertArgs));
        _byeDpi = StartProcess(path, string.Join(' ', args.Select(Quote)), "ByeDPI");
        await WaitForPortAsync(_byeDpi, 10809, "ByeDPI не смог открыть локальный порт 10809.");
        Log?.Invoke("ByeDPI включён.");
    }

    private static JsonObject BuildConfig(DragonProfile profile, DragonSettings settings)
    {
        var proxy = ProfileParser.BuildOutbound(profile, settings);
        var outbounds = new JsonArray(proxy);
        if (settings.ByeDpiEnabled)
        {
            ChainThroughOutbound(proxy, "byedpi-local");
            outbounds.Add(new JsonObject
            {
                ["tag"] = "byedpi-local", ["protocol"] = "socks",
                ["settings"] = new JsonObject
                {
                    ["servers"] = new JsonArray(new JsonObject { ["address"] = "127.0.0.1", ["port"] = 10809 })
                }
            });
        }
        outbounds.Add(new JsonObject { ["tag"] = "direct", ["protocol"] = "freedom" });
        outbounds.Add(new JsonObject { ["tag"] = "block", ["protocol"] = "blackhole" });

        return new JsonObject
        {
            ["log"] = new JsonObject { ["loglevel"] = settings.LogLevel },
            ["inbounds"] = new JsonArray(new JsonObject
            {
                ["tag"] = "dragon-socks", ["listen"] = "127.0.0.1", ["port"] = 10808,
                ["protocol"] = "socks",
                ["settings"] = new JsonObject { ["auth"] = "noauth", ["udp"] = true, ["ip"] = "127.0.0.1" },
                ["sniffing"] = new JsonObject
                {
                    ["enabled"] = true,
                    ["destOverride"] = new JsonArray("http", "tls", "quic", "fakedns")
                }
            }),
            ["outbounds"] = outbounds,
            ["routing"] = new JsonObject
            {
                ["domainStrategy"] = "IPIfNonMatch",
                ["rules"] = BuildRoutingRules(settings)
            }
        };
    }

    private static void ChainThroughOutbound(JsonObject outbound, string dialerTag)
    {
        var stream = outbound["streamSettings"] as JsonObject ?? new JsonObject();
        outbound["streamSettings"] = stream;
        var sockopt = stream["sockopt"] as JsonObject ?? new JsonObject();
        stream["sockopt"] = sockopt;
        sockopt["dialerProxy"] = dialerTag;
        outbound.Remove("proxySettings");
    }

    private static JsonArray BuildRoutingRules(DragonSettings settings)
    {
        var rules = new JsonArray();
        if (settings.RoutingMode == "bypass-lan")
        {
            rules.Add(new JsonObject
            {
                ["type"] = "field", ["ip"] = new JsonArray("geoip:private"), ["outboundTag"] = "direct"
            });
        }
        rules.Add(new JsonObject { ["type"] = "field", ["network"] = "tcp,udp", ["outboundTag"] = "proxy" });
        return rules;
    }

    private static string BuildTunnelConfig() => """
        tunnel:
          name: DragonVPN
          mtu: 8500
          multi-queue: false
          ipv4: 198.18.0.1
        socks5:
          port: 10808
          address: 127.0.0.1
          udp: udp
        mapdns:
          address: 198.18.0.2
          port: 53
          network: 100.64.0.0
          netmask: 255.192.0.0
          cache-size: 10000
        misc:
          log-file: stderr
          log-level: warn
        """;

    private async Task<int> WaitForTunnelAsync()
    {
        var end = DateTime.UtcNow.AddSeconds(8);
        while (DateTime.UtcNow < end)
        {
            if (_tunnel is null || _tunnel.HasExited) break;
            var value = RunPowerShell("(Get-NetAdapter -Name 'DragonVPN' -ErrorAction SilentlyContinue).ifIndex");
            if (int.TryParse(value.Trim(), out var index) && index > 0) return index;
            await Task.Delay(150);
        }
        throw new InvalidOperationException("Не удалось создать системный адаптер DragonVPN.");
    }

    private async Task InstallRoutesAsync(DragonProfile profile, int tunIndex)
    {
        var ips = new List<IPAddress>();
        if (IPAddress.TryParse(profile.Address, out var direct)) ips.Add(direct);
        else ips.AddRange(await Dns.GetHostAddressesAsync(profile.Address));

        foreach (var ip in ips.Where(x => x.AddressFamily == AddressFamily.InterNetwork).Distinct())
        {
            var address = ip.ToString();
            RunPowerShell($"$r=Get-NetRoute -DestinationPrefix '0.0.0.0/0' -AddressFamily IPv4 | Where-Object {{$_.NextHop -ne '0.0.0.0' -and $_.InterfaceIndex -ne {tunIndex}}} | Sort-Object RouteMetric | Select-Object -First 1; if($r){{New-NetRoute -DestinationPrefix '{address}/32' -InterfaceIndex $r.InterfaceIndex -NextHop $r.NextHop -RouteMetric 1 -PolicyStore ActiveStore -ErrorAction Stop | Out-Null}}");
            _bypassAddresses.Add(address);
        }
        RunPowerShell($"New-NetRoute -DestinationPrefix '0.0.0.0/0' -InterfaceIndex {tunIndex} -NextHop '0.0.0.0' -RouteMetric 1 -PolicyStore ActiveStore -ErrorAction Stop | Out-Null");
        RunPowerShell($"Set-DnsClientServerAddress -InterfaceIndex {tunIndex} -ServerAddresses @('198.18.0.2') -ErrorAction SilentlyContinue");
    }

    private void RemoveRoutes()
    {
        if (_tunIndex is int index)
        {
            try { RunPowerShell($"Remove-NetRoute -DestinationPrefix '0.0.0.0/0' -InterfaceIndex {index} -Confirm:$false -ErrorAction SilentlyContinue"); } catch { }
            try { RunPowerShell($"Set-DnsClientServerAddress -InterfaceIndex {index} -ResetServerAddresses -ErrorAction SilentlyContinue"); } catch { }
        }
        foreach (var ip in _bypassAddresses)
        {
            try { RunPowerShell($"Remove-NetRoute -DestinationPrefix '{ip}/32' -Confirm:$false -ErrorAction SilentlyContinue"); } catch { }
        }
        _bypassAddresses.Clear();
        _tunIndex = null;
    }

    private static string RunPowerShell(string command)
    {
        var encoded = Convert.ToBase64String(System.Text.Encoding.Unicode.GetBytes(command));
        using var process = Process.Start(new ProcessStartInfo("powershell.exe", $"-NoProfile -NonInteractive -EncodedCommand {encoded}")
        {
            UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true, CreateNoWindow = true
        }) ?? throw new InvalidOperationException("Не удалось запустить PowerShell.");
        var stdout = process.StandardOutput.ReadToEnd();
        var stderr = process.StandardError.ReadToEnd();
        process.WaitForExit(10000);
        if (process.ExitCode != 0) throw new InvalidOperationException(string.IsNullOrWhiteSpace(stderr) ? "Ошибка настройки системного маршрута." : stderr.Trim());
        return stdout;
    }

    private async Task WaitForPortAsync(Process process, int port, string error)
    {
        var end = DateTime.UtcNow.AddSeconds(5);
        while (DateTime.UtcNow < end)
        {
            if (process.HasExited) break;
            try
            {
                using var client = new TcpClient();
                await client.ConnectAsync("127.0.0.1", port).WaitAsync(TimeSpan.FromMilliseconds(200));
                return;
            }
            catch { await Task.Delay(100); }
        }
        throw new InvalidOperationException(error);
    }

    private Process StartProcess(string file, string args, string name)
    {
        var process = new Process
        {
            StartInfo = new ProcessStartInfo(file, args)
            {
                WorkingDirectory = AppContext.BaseDirectory, UseShellExecute = false,
                RedirectStandardOutput = true, RedirectStandardError = true, CreateNoWindow = true,
            }, EnableRaisingEvents = true
        };
        process.OutputDataReceived += (_, e) => { if (e.Data is not null) Log?.Invoke($"[{name}] {e.Data}"); };
        process.ErrorDataReceived += (_, e) => { if (e.Data is not null) Log?.Invoke($"[{name}] {e.Data}"); };
        process.Exited += (_, _) => Log?.Invoke($"{name} остановлен, код {process.ExitCode}.");
        if (!process.Start()) throw new InvalidOperationException($"Не удалось запустить {name}.");
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        return process;
    }

    private void StopProcess(ref Process? process, string name)
    {
        var current = process;
        process = null;
        if (current is null) return;
        try { if (!current.HasExited) { current.Kill(true); current.WaitForExit(1800); } } catch { }
        current.Dispose();
        Log?.Invoke($"{name} выключен.");
    }

    private static IEnumerable<string> ByeDpiPreset(DragonSettings settings)
    {
        var position = string.IsNullOrWhiteSpace(settings.ByeDpiSplitPosition) ? "1+s" : settings.ByeDpiSplitPosition;
        var ttl = settings.ByeDpiFakeTtl.ToString();
        return settings.ByeDpiStrategy switch
        {
            "split" => ["--split", position],
            "fake" => ["--fake", "-1", "--ttl", ttl],
            "disorder" => ["--disorder", position],
            "strong" => ["--disorder", "1", "--fake", "-1", "--auto=torst", "--split", "1+s", "--disorder", "3+s", "--fake", "-1", "--ttl", ttl],
            _ => ["--split", "1+s", "--disorder", "3+s", "--auto=torst", "--disorder", "1", "--fake", "-1", "--auto=ssl_err", "--fake", "-1", "--ttl", ttl, "--fake-tls-mod", "rand", "--auto=torst", "--tlsrec", "3+s", "--auto=torst", "--oob", "3+s", "--oob-data", "a", "--auto=torst", "--split", "0+sm"]
        };
    }

    private static IEnumerable<string> SplitArgs(string value) => value.Split(' ', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
    private static string Quote(string value) => value.Any(char.IsWhiteSpace) ? $"\"{value.Replace("\"", "\\\"")}\"" : value;
    public void Dispose() => Stop();
}
