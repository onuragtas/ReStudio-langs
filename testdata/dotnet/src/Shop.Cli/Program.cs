using Shop.Application;

var service = new ProductService();
var total = service.TotalWithTax(0.2m);
var later = await service.TotalWithTaxAsync(0.1m);
Console.WriteLine($"{total} {later}");
