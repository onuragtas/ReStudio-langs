using Shop.Domain;

namespace Shop.Application;

public class ProductService
{
    private readonly List<Product> _products = [new("Book", 10m), new("Pen", 2m)];

    public decimal TotalWithTax(decimal rate)
    {
        var total = _products.Sum(p => p.PriceWithTax(rate));
        return total;
    }

    public async Task<decimal> TotalWithTaxAsync(decimal rate)
    {
        await Task.Delay(10);
        var total = TotalWithTax(rate);
        return total;
    }
}
