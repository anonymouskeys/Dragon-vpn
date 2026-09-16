using System.Collections.ObjectModel;
using System.Diagnostics;
using System.Net.Http;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Microsoft.Win32;
using DragonVPN.Models;
using DragonVPN.Services;

namespace DragonVPN;

internal static class SectionDialogs
{
    private static readonly Brush Background = new SolidColorBrush(Color.FromRgb(9, 7, 15));
    private static readonly Brush Purple = new SolidColorBrush(Color.FromRgb(177, 108, 255));

    public static void ShowGroups(Window owner, ObservableCollection<DragonProfile> profiles, DragonSettings settings, SettingsStore store)
    {
        var groups = new ObservableCollection<string>(settings.Groups.Distinct());
        var list = new ListBox { ItemsSource = groups, MinHeight = 300, FontSize = 17 };
        var add = ActionButton("Добавить");
        var rename = ActionButton("Переименовать");
        var remove = ActionButton("Удалить");
        add.Click += (_, _) =>
        {
            var value = TextPrompt.Show(owner, "Группы", "Название новой группы:");
            if (!string.IsNullOrWhiteSpace(value) && !groups.Contains(value)) groups.Add(value.Trim());
        };
        rename.Click += (_, _) =>
        {
            if (list.SelectedItem is not string oldName) return;
            var value = TextPrompt.Show(owner, "Группы", "Новое название:", oldName);
            if (string.IsNullOrWhiteSpace(value)) return;
            var index = groups.IndexOf(oldName);
            groups[index] = value.Trim();
            foreach (var profile in profiles.Where(x => x.Group == oldName)) profile.Group = value.Trim();
        };
        remove.Click += (_, _) =>
        {
            if (list.SelectedItem is not string name || groups.Count <= 1) return;
            groups.Remove(name);
            foreach (var profile in profiles.Where(x => x.Group == name)) profile.Group = groups[0];
        };
        var window = Create(owner, "Группы", list, add, rename, remove);
        window.Closed += (_, _) =>
        {
            settings.Groups = groups.ToList();
            store.SaveSettings(settings);
            store.SaveProfiles(profiles);
        };
        window.ShowDialog();
    }

    public static void ShowRoutes(Window owner, DragonSettings settings, SettingsStore store)
    {
        var label = new TextBlock { Text = "Режим маршрутизации", FontSize = 18, Margin = new Thickness(0, 0, 0, 10) };
        var mode = new ComboBox { FontSize = 17, SelectedValuePath = "Tag" };
        mode.Items.Add(new ComboBoxItem { Content = "Проксировать всё", Tag = "global" });
        mode.Items.Add(new ComboBoxItem { Content = "Локальные сети напрямую", Tag = "bypass-lan" });
        mode.SelectedValue = settings.RoutingMode;
        var save = ActionButton("Сохранить");
        var window = Create(owner, "Маршруты", label, mode, save);
        save.Click += (_, _) =>
        {
            settings.RoutingMode = mode.SelectedValue?.ToString() ?? "bypass-lan";
            store.SaveSettings(settings);
            window.Close();
        };
        window.ShowDialog();
    }

    public static void ShowApplications(Window owner, DragonSettings settings, SettingsStore store)
    {
        var items = new ObservableCollection<string>(settings.Applications);
        var list = new ListBox { ItemsSource = items, MinHeight = 300, FontSize = 15 };
        var mode = new ComboBox { FontSize = 17, SelectedValuePath = "Tag", Margin = new Thickness(0, 0, 0, 12) };
        mode.Items.Add(new ComboBoxItem { Content = "Все приложения", Tag = "all" });
        mode.Items.Add(new ComboBoxItem { Content = "Только выбранные", Tag = "include" });
        mode.Items.Add(new ComboBoxItem { Content = "Кроме выбранных", Tag = "exclude" });
        mode.SelectedValue = settings.ApplicationMode;
        var add = ActionButton("Выбрать EXE");
        var remove = ActionButton("Удалить");
        add.Click += (_, _) =>
        {
            var picker = new OpenFileDialog { Filter = "Приложения Windows (*.exe)|*.exe", Multiselect = true };
            if (picker.ShowDialog(owner) == true)
                foreach (var path in picker.FileNames.Where(path => !items.Contains(path))) items.Add(path);
        };
        remove.Click += (_, _) => { if (list.SelectedItem is string path) items.Remove(path); };
        var window = Create(owner, "Выбор приложений", mode, list, add, remove);
        window.Closed += (_, _) =>
        {
            settings.ApplicationMode = mode.SelectedValue?.ToString() ?? "all";
            settings.Applications = items.ToList();
            store.SaveSettings(settings);
        };
        window.ShowDialog();
    }

    public static void ShowSettings(Window owner, LanguageService language)
    {
        var title = new TextBlock { Text = "Язык интерфейса", FontSize = 18, Margin = new Thickness(0, 0, 0, 10) };
        var languages = new ComboBox { FontSize = 18, SelectedValuePath = "Tag" };
        languages.Items.Add(new ComboBoxItem { Content = "English", Tag = "en" });
        languages.Items.Add(new ComboBoxItem { Content = "Русский", Tag = "ru" });
        languages.Items.Add(new ComboBoxItem { Content = "中文", Tag = "zh-CN" });
        languages.Items.Add(new ComboBoxItem { Content = "فارسی", Tag = "fa", FlowDirection = FlowDirection.RightToLeft });
        languages.SelectedValue = language.Code;
        var save = ActionButton("Сохранить");
        var window = Create(owner, "Настройки", title, languages, save);
        save.Click += (_, _) =>
        {
            LanguageService.SaveSelectedLanguage(languages.SelectedValue?.ToString() ?? "en");
            MessageBox.Show(window, "Язык применится после перезапуска DragonVPN.", "Dragon VPN");
            window.Close();
        };
        window.ShowDialog();
    }

    public static void ShowTools(Window owner, DragonSettings settings, SettingsStore store)
    {
        var fragment = new CheckBox { Content = "Фрагментация", IsChecked = settings.FragmentEnabled, FontSize = 18 };
        var packets = Field("Пакеты", settings.FragmentPackets);
        var length = Field("Длина", settings.FragmentLength);
        var interval = Field("Интервал", settings.FragmentInterval);
        var maxSplit = Field("maxSplit (0 — без ограничения)", settings.FragmentMaxSplit.ToString());
        var dpi = new CheckBox { Content = "ByeDPI", IsChecked = settings.ByeDpiEnabled, FontSize = 18, Margin = new Thickness(0, 20, 0, 0) };
        var strategy = Field("Стратегия", settings.ByeDpiStrategy);
        var split = Field("Позиция разделения", settings.ByeDpiSplitPosition);
        var ttl = Field("Fake TTL", settings.ByeDpiFakeTtl.ToString());
        var expert = Field("Дополнительные аргументы", settings.ByeDpiExpertArgs);
        var save = ActionButton("Сохранить");
        var window = Create(owner, "Инструменты", fragment, packets.Panel, length.Panel, interval.Panel, maxSplit.Panel,
            dpi, strategy.Panel, split.Panel, ttl.Panel, expert.Panel, save);
        save.Click += (_, _) =>
        {
            settings.FragmentEnabled = fragment.IsChecked == true;
            settings.FragmentPackets = packets.Box.Text.Trim();
            settings.FragmentLength = length.Box.Text.Trim();
            settings.FragmentInterval = interval.Box.Text.Trim();
            settings.FragmentMaxSplit = int.TryParse(maxSplit.Box.Text, out var value) ? Math.Max(0, value) : 0;
            settings.ByeDpiEnabled = dpi.IsChecked == true;
            settings.ByeDpiStrategy = strategy.Box.Text.Trim();
            settings.ByeDpiSplitPosition = split.Box.Text.Trim();
            settings.ByeDpiFakeTtl = int.TryParse(ttl.Box.Text, out var fakeTtl) ? Math.Clamp(fakeTtl, 1, 255) : 8;
            settings.ByeDpiExpertArgs = expert.Box.Text.Trim();
            store.SaveSettings(settings);
            window.Close();
        };
        window.ShowDialog();
    }

    public static void ShowLogs(Window owner, ObservableCollection<string> logs)
    {
        var list = new ListBox { ItemsSource = logs, FontFamily = new FontFamily("Consolas"), FontSize = 13, MinHeight = 500 };
        var copy = ActionButton("Копировать всё");
        copy.Click += (_, _) => Clipboard.SetText(string.Join(Environment.NewLine, logs));
        Create(owner, "Журналы", list, copy).ShowDialog();
    }

    public static void ShowAbout(Window owner)
    {
        var title = new TextBlock { Text = "Dragon VPN", FontSize = 30, FontWeight = FontWeights.Bold };
        var author = new TextBlock { Text = "Anonymous Keys", Foreground = Brushes.DeepSkyBlue, FontSize = 18 };
        var info = new TextBlock
        {
            Text = "Официальная Windows-версия DragonVPN.\nИнтерфейс и настройки перенесены из Android-приложения.",
            TextWrapping = TextWrapping.Wrap, FontSize = 16, Margin = new Thickness(0, 18, 0, 0)
        };
        Create(owner, "О приложении", title, author, info).ShowDialog();
    }

    public static void OpenDocuments() => Process.Start(new ProcessStartInfo("https://github.com/anonymouskeys/Dragon-vpn") { UseShellExecute = true });

    public static async Task CheckUpdateAsync(Window owner)
    {
        try
        {
            using var http = new HttpClient();
            http.DefaultRequestHeaders.UserAgent.ParseAdd("DragonVPN-Windows/1.0");
            using var json = JsonDocument.Parse(await http.GetStringAsync("https://api.github.com/repos/anonymouskeys/Dragon-vpn/releases/latest"));
            var tag = json.RootElement.TryGetProperty("tag_name", out var value) ? value.GetString() : null;
            MessageBox.Show(owner, string.IsNullOrWhiteSpace(tag) ? "Обновления не найдены." : $"Последняя версия: {tag}", "Dragon VPN");
        }
        catch (Exception exception) { MessageBox.Show(owner, "Не удалось проверить обновление:\n" + exception.Message, "Dragon VPN"); }
    }

    private static (StackPanel Panel, TextBox Box) Field(string title, string value)
    {
        var box = new TextBox { Text = value, FontSize = 16, Padding = new Thickness(8) };
        var panel = new StackPanel { Margin = new Thickness(0, 8, 0, 0) };
        panel.Children.Add(new TextBlock { Text = title, Foreground = Brushes.LightGray });
        panel.Children.Add(box);
        return (panel, box);
    }

    private static Button ActionButton(string text) => new()
    {
        Content = text, Background = Purple, Foreground = Brushes.White, BorderThickness = new Thickness(0),
        Padding = new Thickness(16, 9, 16, 9), Margin = new Thickness(5, 12, 5, 0), FontSize = 15
    };

    private static Window Create(Window owner, string title, params UIElement[] elements)
    {
        var content = new StackPanel { Margin = new Thickness(24) };
        foreach (var element in elements) content.Children.Add(element);
        return new Window
        {
            Owner = owner, Title = title, Icon = owner.Icon, Width = 620, Height = 700,
            MinHeight = 400, WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = Background, Foreground = Brushes.White,
            Content = new ScrollViewer { Content = content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto }
        };
    }
}
