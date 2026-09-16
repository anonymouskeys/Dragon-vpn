namespace DragonVPN.Models;

public sealed class DragonSettings
{
    public bool FragmentEnabled { get; set; }
    public string FragmentPackets { get; set; } = "tlshello";
    public string FragmentLength { get; set; } = "50-100";
    public string FragmentInterval { get; set; } = "10-20";
    public int FragmentMaxSplit { get; set; } = 0;

    public bool ByeDpiEnabled { get; set; }
    public string ByeDpiStrategy { get; set; } = "auto";
    public string ByeDpiSplitPosition { get; set; } = "1+s";
    public int ByeDpiFakeTtl { get; set; } = 8;
    public int ByeDpiFakeCount { get; set; } = 1;
    public int ByeDpiDelayMs { get; set; }
    public bool ByeDpiPortsOnly { get; set; } = true;
    public string ByeDpiExpertArgs { get; set; } = "";

    public List<string> Groups { get; set; } = ["Подписка"];
    public string RoutingMode { get; set; } = "bypass-lan";
    public string ApplicationMode { get; set; } = "all";
    public List<string> Applications { get; set; } = [];
    public string LogLevel { get; set; } = "warning";
}
