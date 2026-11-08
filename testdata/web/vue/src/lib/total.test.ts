import { describe, expect, it } from 'vitest'
import { total } from './total'

describe('total', () => {
  it('adds the prices', () => {
    expect(total([1, 2, 3])).toBe(6)
  })
  it('returns zero for no prices', () => {
    expect(total([])).toBe(0)
  })
})
