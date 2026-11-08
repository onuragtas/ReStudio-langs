using Shop.Domain;

namespace Shop.Domain.Tests;

public class ProductTests
{
    [Fact]
    public void PriceWithTaxAddsTheRate()
    {
        var product = new Product("Book", 10m);
        Assert.Equal(12m, product.PriceWithTax(0.2m));
    }
}
