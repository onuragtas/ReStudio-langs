using Shop.Domain;

namespace Shop.Application;

public class ProductService
{
    private readonly List<Product> _products = [new("Book", 10m), new("Pen", 2m)];

    public decimal TotalWithTax(decimal rate) => _products.Sum(p => p.PriceWithTax(rate));
}
