**Контракты equals()**
- Если x != null, то `x.equals(x) = true`
- Если `x.equals(y) = true`, то `y.equals(x) = true`
- Если `x.equals(y) = true` и `z.equals(y) = true`, то `x.equals(z) = true`
- `x.equals(null) = false`
- При неизменных полях объектов многократные вызовы `x.equals(y)` должны возвращать один и тот же результат

**Контракты hashCode()**
- Если объекты равны по equals(), то у них всегда одинаковый hashCode()
- У неравных по equals() объектов hashCode может совпадать
---
