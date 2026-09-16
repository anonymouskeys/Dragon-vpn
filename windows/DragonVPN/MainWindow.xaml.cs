using System.Collections.ObjectModel;
using System.Net.Http;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using DragonVPN.Models;
using DragonVPN.Services;

namespace DragonVPN;

public partial class MainWindow : Window
{
    private readonly SettingsStore _store = new();
    private readonly ObservableCollection<DragonProfile> _profiles;
    private readonly ObservableCollection<string> _logs = [];
    private readonly DragonRuntime _runtime;
    private readonly LanguageService _language = new();
    private DragonSettings _settings;
    private bool _loading = true;

    public MainWindow()
    {
        InitializeComponent();
        _settings = _store.LoadSettings();
        _profiles = new ObservableCollection<DragonProfile>(_store.LoadProfiles());
        ProfilesList.ItemsSource = _profiles;
        _runtime = new DragonRuntime(_store);
        _runtime.Log += line => Dispatcher.Invoke(() =>
        {
            _logs.Add($"{DateTime.Now:HH:mm:ss}  {line}");
            if (_logs.Count > 2000) _logs.RemoveAt(0);
        });
        DpiToggle.IsChecked = _settings.ByeDpiEnabled;
        ApplyLanguage();
        RefreshTabCounts();
        _loading = false;
        Closed += (_, _) => _runtime.Dispose();
    }

    private void ApplyLanguage()
    {
        FlowDirection = _language.IsRightToLeft ? FlowDirection.RightToLeft : FlowDirection.LeftToRight;
        ProfileTitle.Text = _language.T("title_server", _language.T("nav_configuration", "Профиль"));
        NavConfiguration.Content = "▱    " + _language.T("nav_configuration", "Конфигурация");
        NavGroups.Content = "▤    " + _language.T("nav_groups", "Группы");
        NavRoutes.Content = "⑂    " + _language.T("nav_routes", "Маршруты");
        NavApps.Content = "▦    " + _language.T("per_app_proxy_settings", "Выбор приложений");
        NavSettings.Content = "⚙    " + _language.T("title_settings", "Настройки");
        NavLogs.Content = "▣    " + _language.T("nav_logs", "Журналы");
        NavTools.Content = "⟲    " + _language.T("nav_tools", "Инструменты");
        NavDocuments.Content = "▱    " + _language.T("nav_documents", "Документы");
        NavAbout.Content = "ⓘ    " + _language.T("title_about", "О приложении");
        NavUpdate.Content = "◴    " + _language.T("update_check_for_update", "Проверить обновление");
        ConnectionStatus.Text = _language.T("connection_not_connected", "Нет соединения");
    }

    private void MenuButton_Click(object sender, RoutedEventArgs e) => SetDrawer(true);
    private void DrawerShade_MouseDown(object sender, MouseButtonEventArgs e) => SetDrawer(false);
    private void SetDrawer(bool open)
    {
        DrawerShade.Visibility = open ? Visibility.Visible : Visibility.Collapsed;
        DrawerPanel.Visibility = open ? Visibility.Visible : Visibility.Collapsed;
    }

    private async void DrawerNavigation_Click(object sender, RoutedEventArgs e)
    {
        SetDrawer(false);
        var tag = (sender as Button)?.Tag?.ToString();
        if (tag == "configuration") return;
        switch (tag)
        {
            case "groups": SectionDialogs.ShowGroups(this, _profiles, _settings, _store); RefreshTabCounts(); break;
            case "routes": SectionDialogs.ShowRoutes(this, _settings, _store); break;
            case "apps": SectionDialogs.ShowApplications(this, _settings, _store); break;
            case "settings": SectionDialogs.ShowSettings(this, _language); break;
            case "logs": SectionDialogs.ShowLogs(this, _logs); break;
            case "tools": SectionDialogs.ShowTools(this, _settings, _store); DpiToggle.IsChecked = _settings.ByeDpiEnabled; break;
            case "documents": SectionDialogs.OpenDocuments(); break;
            case "about": SectionDialogs.ShowAbout(this); break;
            case "update": await SectionDialogs.CheckUpdateAsync(this); break;
        }
    }

    private void DpiToggle_Changed(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        _settings.ByeDpiEnabled = DpiToggle.IsChecked == true;
        _store.SaveSettings(_settings);
    }

    private void Filter_Click(object sender, RoutedEventArgs e)
    {
        var value = TextPrompt.Show(this, "Фильтр", "Введите часть названия или адреса:");
        ProfilesList.Items.Filter = item => string.IsNullOrWhiteSpace(value) || item is DragonProfile p &&
            (p.Name.Contains(value, StringComparison.OrdinalIgnoreCase) || p.Endpoint.Contains(value, StringComparison.OrdinalIgnoreCase));
    }

    private void More_Click(object sender, RoutedEventArgs e)
    {
        var menu = new ContextMenu();
        AddMenu(menu, "Импортировать из буфера", (_, _) => PasteProfiles());
        AddMenu(menu, "Добавить вручную", (_, _) => AddLink());
        AddMenu(menu, "Обновить подписку", async (_, _) => await UpdateSubscriptionAsync());
        AddMenu(menu, "Удалить дубликаты", (_, _) => RemoveDuplicates());
        menu.IsOpen = true;
    }

    private static void AddMenu(ContextMenu menu, string title, RoutedEventHandler action)
    {
        var item = new MenuItem { Header = title };
        item.Click += action;
        menu.Items.Add(item);
    }

    private void AddLink()
    {
        var value = TextPrompt.Show(this, "Добавить конфигурацию", "Вставьте ссылку профиля:");
        ImportText(value);
    }

    private void PasteProfiles()
    {
        if (!Clipboard.ContainsText()) { MessageBox.Show(this, "В буфере обмена нет текста."); return; }
        ImportText(Clipboard.GetText());
    }

    private async Task UpdateSubscriptionAsync()
    {
        var url = TextPrompt.Show(this, "Обновить подписку", "Введите HTTPS-ссылку подписки:");
        if (string.IsNullOrWhiteSpace(url)) return;
        try
        {
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(25) };
            http.DefaultRequestHeaders.UserAgent.ParseAdd("DragonVPN-Windows/1.0");
            ImportText(await http.GetStringAsync(url));
        }
        catch (Exception ex) { MessageBox.Show(this, "Не удалось получить подписку:\n" + ex.Message, "DragonVPN"); }
    }

    private void ImportText(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return;
        var parsed = ProfileParser.ParseMany(text);
        foreach (var p in parsed.Where(p => _profiles.All(x => x.RawUri != p.RawUri))) _profiles.Add(p);
        _store.SaveProfiles(_profiles);
        RefreshTabCounts();
        if (parsed.Count > 0) ProfilesList.SelectedItem = parsed[0];
        else MessageBox.Show(this, "Поддерживаемых конфигураций не найдено.", "DragonVPN");
    }

    private void RemoveDuplicates()
    {
        var unique = _profiles.GroupBy(p => p.RawUri).Select(g => g.First()).ToList();
        _profiles.Clear(); foreach (var p in unique) _profiles.Add(p);
        _store.SaveProfiles(_profiles); RefreshTabCounts();
    }

    private void RefreshTabCounts()
    {
        GroupTabs.Children.Clear();
        var groups = _settings.Groups.Where(name => !string.IsNullOrWhiteSpace(name)).Distinct().ToList();
        foreach (var profileGroup in _profiles.Select(profile => profile.Group).Where(name => !string.IsNullOrWhiteSpace(name)).Distinct())
            if (!groups.Contains(profileGroup)) groups.Add(profileGroup);
        if (groups.Count == 0) groups.Add("Подписка");
        _settings.Groups = groups;
        foreach (var group in groups)
        {
            var button = new Button
            {
                Content = $"{group} ({_profiles.Count(profile => profile.Group == group)})", Tag = group,
                Background = Brushes.Transparent, Foreground = Brushes.White, FontSize = 17,
                Padding = new Thickness(18, 10, 18, 10), BorderBrush = new SolidColorBrush(Color.FromRgb(177, 108, 255)),
                BorderThickness = new Thickness(0, 0, 0, 0)
            };
            button.Click += (_, _) =>
            {
                ProfilesList.Items.Filter = item => item is DragonProfile profile && profile.Group == group;
                foreach (var tab in GroupTabs.Children.OfType<Button>())
                {
                    tab.Background = ReferenceEquals(tab, button) ? new SolidColorBrush(Color.FromRgb(60, 23, 100)) : Brushes.Transparent;
                    tab.BorderThickness = ReferenceEquals(tab, button) ? new Thickness(0, 0, 0, 4) : new Thickness(0);
                }
            };
            GroupTabs.Children.Add(button);
        }
        _store.SaveSettings(_settings);
    }

    private void ProfilesList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        foreach (var p in _profiles) p.Selected = ReferenceEquals(p, ProfilesList.SelectedItem);
        ProfilesList.Items.Refresh();
    }

    private void ShareProfile_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is DragonProfile p) Clipboard.SetText(p.RawUri);
    }

    private void EditProfile_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is not DragonProfile p) return;
        var value = TextPrompt.Show(this, "Изменить профиль", "Ссылка конфигурации:", p.RawUri);
        var replacement = string.IsNullOrWhiteSpace(value) ? null : ProfileParser.Parse(value);
        if (replacement is null) return;
        var index = _profiles.IndexOf(p); _profiles[index] = replacement; _store.SaveProfiles(_profiles);
    }

    private void DeleteProfile_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is not DragonProfile p) return;
        _profiles.Remove(p); _store.SaveProfiles(_profiles); RefreshTabCounts();
    }

    private async void Connect_Click(object sender, RoutedEventArgs e)
    {
        if (_runtime.IsRunning)
        {
            _runtime.Stop(); SetConnected(false); return;
        }
        if (ProfilesList.SelectedItem is not DragonProfile profile)
        {
            MessageBox.Show(this, "Сначала выберите профиль.", "DragonVPN"); return;
        }
        ConnectButton.IsEnabled = false;
        try { await _runtime.StartAsync(profile, _settings); SetConnected(true); }
        catch (Exception ex) { _runtime.Stop(); SetConnected(false); MessageBox.Show(this, ex.Message, "DragonVPN", MessageBoxButton.OK, MessageBoxImage.Error); }
        finally { ConnectButton.IsEnabled = true; }
    }

    private void SetConnected(bool connected)
    {
        ConnectionStatus.Text = connected ? "Соединение установлено" : "Нет соединения";
        ConnectButton.Content = connected ? "■" : "▶";
        ConnectButton.Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString(connected ? "#B16CFF" : "#6D637A"));
    }
}

internal static class TextPrompt
{
    public static string? Show(Window owner, string title, string message, string initial = "")
    {
        var input = new TextBox { MinWidth = 500, MinHeight = 70, Text = initial, AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 12, 0, 12) };
        var ok = new Button { Content = "Сохранить", IsDefault = true, MinWidth = 110 };
        var cancel = new Button { Content = "Отмена", IsCancel = true, MinWidth = 90, Background = Brushes.DimGray };
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        buttons.Children.Add(cancel); buttons.Children.Add(ok);
        var panel = new StackPanel { Margin = new Thickness(20) };
        panel.Children.Add(new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap }); panel.Children.Add(input); panel.Children.Add(buttons);
        var dialog = new Window { Title = title, Owner = owner, Width = 590, SizeToContent = SizeToContent.Height, WindowStartupLocation = WindowStartupLocation.CenterOwner, ResizeMode = ResizeMode.NoResize, Content = panel };
        ok.Click += (_, _) => { dialog.DialogResult = true; dialog.Close(); };
        input.Focus(); return dialog.ShowDialog() == true ? input.Text : null;
    }
}
