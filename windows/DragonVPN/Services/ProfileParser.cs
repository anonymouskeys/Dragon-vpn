using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using DragonVPN.Models;

namespace DragonVPN.Services;

public static class ProfileParser
{
    public static List<DragonProfile> ParseMany(string input)
    {
        input = input.Trim();
        if (input.Length == 0) return [];
        if (!input.Contains("://", StringComparison.Ordinal))
        {
            try
            {
                var decoded = Encoding.UTF8.GetString(Convert.FromBase64String(PadBase64(input)));
                if (decoded.Contains("://", StringComparison.Ordinal)) input = decoded;
            }
            catch { }
        }
        return input.Replace("\r", "").Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(Parse).Where(profile => profile is not null).Cast<DragonProfile>().ToList();
    }

    public static DragonProfile? Parse(string raw)
    {
        raw = raw.Trim();
        if (raw.StartsWith("vmess://", StringComparison.OrdinalIgnoreCase)) return ParseVmess(raw);
        if (!Uri.TryCreate(raw, UriKind.Absolute, out var uri)) return null;
        var protocol = uri.Scheme.ToLowerInvariant();
        if (protocol is not ("vless" or "trojan" or "ss" or "socks" or "socks5" or "http" or "https")) return null;
        var name = Decode(uri.Fragment.TrimStart('#'));
        return new DragonProfile
        {
            Name = string.IsNullOrWhiteSpace(name) ? $"{protocol.ToUpperInvariant()} · {uri.Host}" : name,
            Protocol = protocol == "socks5" ? "socks" : protocol,
            Address = uri.Host,
            Port = uri.Port > 0 ? uri.Port : DefaultPort(protocol),
            RawUri = raw,
        };
    }

    private static DragonProfile? ParseVmess(string raw)
    {
        try
        {
            var encoded = raw[8..].Split('#')[0];
            using var document = JsonDocument.Parse(Encoding.UTF8.GetString(Convert.FromBase64String(PadBase64(encoded))));
            var root = document.RootElement;
            var address = Text(root, "add");
            var port = int.TryParse(Text(root, "port"), out var value) ? value : 443;
            var name = Text(root, "ps");
            return new DragonProfile
            {
                Name = string.IsNullOrWhiteSpace(name) ? $"VMESS · {address}" : name,
                Protocol = "vmess", Address = address, Port = port, RawUri = raw,
            };
        }
        catch { return null; }
    }

    /// <summary>Builds the native Xray outbound used by the Android DragonVPN core.</summary>
    public static JsonObject BuildOutbound(DragonProfile profile, DragonSettings settings)
    {
        if (profile.Protocol == "vmess") return BuildVmess(profile, settings);
        var uri = new Uri(profile.RawUri);
        var query = Query(uri.Query);
        var outbound = new JsonObject { ["tag"] = "proxy", ["protocol"] = profile.Protocol };

        switch (profile.Protocol)
        {
            case "vless":
                var vlessUser = new JsonObject
                {
                    ["id"] = Decode(uri.UserInfo), ["encryption"] = Empty(Value(query, "encryption"), "none")
                };
                Put(vlessUser, "flow", Value(query, "flow"));
                outbound["settings"] = VNext(profile, vlessUser);
                break;
            case "trojan":
                var trojan = new JsonObject
                {
                    ["address"] = profile.Address, ["port"] = profile.Port, ["password"] = Decode(uri.UserInfo)
                };
                Put(trojan, "flow", Value(query, "flow"));
                outbound["settings"] = new JsonObject { ["servers"] = new JsonArray(trojan) };
                break;
            case "ss":
                var (method, password) = ParseShadowsocks(uri);
                outbound["protocol"] = "shadowsocks";
                outbound["settings"] = new JsonObject
                {
                    ["servers"] = new JsonArray(new JsonObject
                    {
                        ["address"] = profile.Address, ["port"] = profile.Port,
                        ["method"] = method, ["password"] = password
                    })
                };
                break;
            case "socks":
            case "http":
            case "https":
                var server = new JsonObject { ["address"] = profile.Address, ["port"] = profile.Port };
                AddProxyCredentials(server, uri.UserInfo);
                outbound["protocol"] = profile.Protocol == "https" ? "http" : profile.Protocol;
                outbound["settings"] = new JsonObject { ["servers"] = new JsonArray(server) };
                break;
            default:
                throw new NotSupportedException($"Протокол {profile.Protocol} пока не поддерживается Windows-ядром.");
        }

        ApplyStream(outbound, query, settings, profile.Address);
        return outbound;
    }

    private static JsonObject BuildVmess(DragonProfile profile, DragonSettings settings)
    {
        var encoded = profile.RawUri[8..].Split('#')[0];
        using var document = JsonDocument.Parse(Encoding.UTF8.GetString(Convert.FromBase64String(PadBase64(encoded))));
        var root = document.RootElement;
        var user = new JsonObject
        {
            ["id"] = Text(root, "id"),
            ["alterId"] = int.TryParse(Text(root, "aid"), out var alterId) ? alterId : 0,
            ["security"] = Empty(Text(root, "scy"), "auto")
        };
        var outbound = new JsonObject
        {
            ["tag"] = "proxy", ["protocol"] = "vmess",
            ["settings"] = VNext(profile, user)
        };
        var query = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            ["security"] = Text(root, "tls"), ["type"] = Text(root, "net"),
            ["path"] = Text(root, "path"), ["host"] = Text(root, "host"),
            ["sni"] = Text(root, "sni"), ["fp"] = Text(root, "fp"),
        };
        ApplyStream(outbound, query, settings, profile.Address);
        return outbound;
    }

    private static JsonObject VNext(DragonProfile profile, JsonObject user) => new()
    {
        ["vnext"] = new JsonArray(new JsonObject
        {
            ["address"] = profile.Address, ["port"] = profile.Port,
            ["users"] = new JsonArray(user)
        })
    };

    private static void ApplyStream(JsonObject outbound, Dictionary<string, string> query, DragonSettings settings, string address)
    {
        var network = Empty(Value(query, "type"), Empty(Value(query, "net"), "tcp"));
        if (network == "raw") network = "tcp";
        var security = Value(query, "security");
        var stream = new JsonObject { ["network"] = network, ["security"] = string.IsNullOrWhiteSpace(security) ? "none" : security };

        if (security == "tls")
        {
            var tls = new JsonObject
            {
                ["serverName"] = Empty(Value(query, "sni"), address),
                ["allowInsecure"] = Bool(Value(query, "allowInsecure")),
                ["fingerprint"] = Empty(Value(query, "fp"), "chrome")
            };
            var alpn = Value(query, "alpn");
            if (!string.IsNullOrWhiteSpace(alpn))
            {
                var protocols = new JsonArray();
                foreach (var protocol in alpn.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
                    protocols.Add(protocol);
                tls["alpn"] = protocols;
            }
            stream["tlsSettings"] = tls;
        }
        else if (security == "reality")
        {
            stream["realitySettings"] = new JsonObject
            {
                ["serverName"] = Empty(Value(query, "sni"), address),
                ["fingerprint"] = Empty(Value(query, "fp"), "chrome"),
                ["publicKey"] = Value(query, "pbk"),
                ["shortId"] = Value(query, "sid"),
                ["spiderX"] = Value(query, "spx")
            };
        }

        switch (network)
        {
            case "ws":
                var headers = new JsonObject();
                Put(headers, "Host", Value(query, "host"));
                stream["wsSettings"] = new JsonObject
                {
                    ["path"] = Empty(Value(query, "path"), "/"), ["headers"] = headers
                };
                break;
            case "grpc":
                stream["grpcSettings"] = new JsonObject
                {
                    ["serviceName"] = Empty(Value(query, "serviceName"), Value(query, "path")),
                    ["authority"] = Value(query, "authority"), ["multiMode"] = Bool(Value(query, "mode"))
                };
                break;
            case "xhttp":
            case "splithttp":
                stream["network"] = "xhttp";
                stream["xhttpSettings"] = new JsonObject
                {
                    ["host"] = Value(query, "host"), ["path"] = Empty(Value(query, "path"), "/"),
                    ["mode"] = Empty(Value(query, "mode"), "auto")
                };
                break;
            case "httpupgrade":
                stream["httpupgradeSettings"] = new JsonObject
                {
                    ["host"] = Value(query, "host"), ["path"] = Empty(Value(query, "path"), "/")
                };
                break;
        }

        if (settings.FragmentEnabled && security is ("tls" or "reality"))
        {
            stream["finalmask"] = new JsonObject
            {
                ["tcp"] = new JsonArray(new JsonObject
                {
                    ["type"] = "fragment",
                    ["settings"] = new JsonObject
                    {
                        ["packets"] = security == "reality" && settings.FragmentPackets == "tlshello" ? "1-3" : settings.FragmentPackets,
                        ["length"] = settings.FragmentLength,
                        ["delay"] = settings.FragmentInterval,
                        ["maxSplit"] = settings.FragmentMaxSplit
                    }
                })
            };
        }
        outbound["streamSettings"] = stream;
    }

    private static void AddProxyCredentials(JsonObject server, string userInfo)
    {
        if (string.IsNullOrWhiteSpace(userInfo)) return;
        var parts = userInfo.Split(':', 2);
        server["users"] = new JsonArray(new JsonObject
        {
            ["user"] = Decode(parts[0]), ["pass"] = parts.Length == 2 ? Decode(parts[1]) : ""
        });
    }

    private static (string Method, string Password) ParseShadowsocks(Uri uri)
    {
        var user = Decode(uri.UserInfo);
        if (!user.Contains(':'))
        {
            try { user = Encoding.UTF8.GetString(Convert.FromBase64String(PadBase64(user))); } catch { }
        }
        var parts = user.Split(':', 2);
        if (parts.Length != 2) throw new FormatException("Некорректная Shadowsocks-ссылка.");
        return (parts[0], parts[1]);
    }

    private static Dictionary<string, string> Query(string query) => query.TrimStart('?')
        .Split('&', StringSplitOptions.RemoveEmptyEntries).Select(value => value.Split('=', 2))
        .ToDictionary(value => Decode(value[0]), value => value.Length > 1 ? Decode(value[1]) : "", StringComparer.OrdinalIgnoreCase);

    private static bool Bool(string value) => value is "1" or "true" or "multi";
    private static string Value(Dictionary<string, string> query, string key) => query.TryGetValue(key, out var value) ? value : "";
    private static string Text(JsonElement element, string key) => element.TryGetProperty(key, out var value) ? value.ToString() : "";
    private static string Empty(string value, string fallback) => string.IsNullOrWhiteSpace(value) ? fallback : value;
    private static void Put(JsonObject target, string key, string value) { if (!string.IsNullOrWhiteSpace(value)) target[key] = value; }
    private static string Decode(string value) => Uri.UnescapeDataString(value.Replace('+', ' '));
    private static int DefaultPort(string protocol) => protocol == "http" ? 80 : 443;
    private static string PadBase64(string value)
    {
        value = value.Replace('-', '+').Replace('_', '/');
        return value.PadRight(value.Length + (4 - value.Length % 4) % 4, '=');
    }
}
