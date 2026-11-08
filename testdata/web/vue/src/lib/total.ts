export function total(prices: number[]): number {
  return prices.reduce((sum, p) => sum + p, 0)
}
