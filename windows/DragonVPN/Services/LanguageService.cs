using System.Globalization;
using System.Xml.Linq;
using Microsoft.Win32;

namespace DragonVPN.Services;

public sealed class LanguageService
{
    private readonly Dictionary<string, string> _strings = new(StringComparer.Ordinal);
    public string Code { get; }
    public bool IsRightToLeft => Code == "fa";

    public LanguageService()
    {
        Code = Normalize(ReadSelectedLanguage());
        Load("en");
        if (Code != "en") Load(Code);
        AddWindowsNavigationStrings();
    }

    public string T(string key, string fallback) => _strings.TryGetValue(key, out var value) && !string.IsNullOrWhiteSpace(value) ? value : fallback;

    public static void SaveSelectedLanguage(string code)
    {
        using var key = Registry.CurrentUser.CreateSubKey(@"Software\AnonymousKeys\DragonVPN");
        key.SetValue("Language", Normalize(code));
    }

    private static string ReadSelectedLanguage()
    {
        using var key = Registry.CurrentUser.OpenSubKey(@"Software\AnonymousKeys\DragonVPN");
        var configured = key?.GetValue("Language")?.ToString();
        return string.IsNullOrWhiteSpace(configured) ? CultureInfo.CurrentUICulture.Name : configured;
    }

    private void Load(string code)
    {
        var directory = Path.Combine(AppContext.BaseDirectory, "lang", code);
        if (!Directory.Exists(directory)) return;
        foreach (var path in Directory.EnumerateFiles(directory, "*.xml"))
        {
            try
            {
                foreach (var element in XDocument.Load(path).Root?.Elements("string") ?? [])
                {
                    var name = element.Attribute("name")?.Value;
                    if (!string.IsNullOrWhiteSpace(name)) _strings[name] = element.Value.Replace("\\'", "'");
                }
            }
            catch { }
        }
    }

    private void AddWindowsNavigationStrings()
    {
        var values = Code switch
        {
            "ru" => new[] { "Конфигурация", "Группы", "Маршруты", "Журналы", "Инструменты", "Документы" },
            "zh-CN" => new[] { "配置", "分组", "路由", "日志", "工具", "文档" },
            "fa" => new[] { "پیکربندی", "گروه‌ها", "مسیرها", "گزارش‌ها", "ابزارها", "اسناد" },
            _ => new[] { "Configuration", "Groups", "Routes", "Logs", "Tools", "Documents" }
        };
        var keys = new[] { "nav_configuration", "nav_groups", "nav_routes", "nav_logs", "nav_tools", "nav_documents" };
        for (var index = 0; index < keys.Length; index++)
            if (!_strings.ContainsKey(keys[index]) || Code is "zh-CN" or "fa") _strings[keys[index]] = values[index];
    }

    private static string Normalize(string code)
    {
        code = code.ToLowerInvariant();
        if (code.StartsWith("ru")) return "ru";
        if (code.StartsWith("zh")) return "zh-CN";
        if (code.StartsWith("fa") || code.StartsWith("ir")) return "fa";
        return "en";
    }
}
