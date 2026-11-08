// A small program for the debug adapter probe.
function total(prices) {
  let sum = 0;
  for (const price of prices) {
    sum += price;
  }
  return sum;
}

console.log(total([10, 2, 5]));
