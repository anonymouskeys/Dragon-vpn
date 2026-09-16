namespace DragonVPN.Models;

public sealed class DragonProfile
{
    public string Name { get; set; } = "Новый профиль";
    public string Protocol { get; set; } = "";
    public string Address { get; set; } = "";
    public int Port { get; set; }
    public string RawUri { get; set; } = "";
    public bool Selected { get; set; }
    public string Group { get; set; } = "Подписка";

    public string Endpoint => string.IsNullOrWhiteSpace(Address) ? "—" : $"{Address}:{Port}";
    public string ProtocolLabel => Protocol.ToUpperInvariant();
}
