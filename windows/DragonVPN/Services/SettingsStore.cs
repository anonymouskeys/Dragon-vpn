using System.Text.Json;
using DragonVPN.Models;

namespace DragonVPN.Services;

public sealed class SettingsStore
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };
    private readonly string _directory = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "DragonVPN");

    public string RuntimeDirectory => Path.Combine(_directory, "runtime");
    private string SettingsPath => Path.Combine(_directory, "settings.json");
    private string ProfilesPath => Path.Combine(_directory, "profiles.json");

    public SettingsStore()
    {
        Directory.CreateDirectory(_directory);
        Directory.CreateDirectory(RuntimeDirectory);
    }

    public DragonSettings LoadSettings() => Load(SettingsPath, new DragonSettings());
    public List<DragonProfile> LoadProfiles() => Load(ProfilesPath, new List<DragonProfile>());
    public void SaveSettings(DragonSettings value) => Save(SettingsPath, value);
    public void SaveProfiles(IEnumerable<DragonProfile> value) => Save(ProfilesPath, value.ToList());

    private static T Load<T>(string path, T fallback)
    {
        try
        {
            return File.Exists(path)
                ? JsonSerializer.Deserialize<T>(File.ReadAllText(path), JsonOptions) ?? fallback
                : fallback;
        }
        catch { return fallback; }
    }

    private static void Save<T>(string path, T value) =>
        File.WriteAllText(path, JsonSerializer.Serialize(value, JsonOptions));
}
