namespace Shop.Domain;

public record Product(string Name, decimal Price)
{
    public decimal PriceWithTax(decimal rate) => Price * (1 + rate);
}
