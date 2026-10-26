import { useEffect, useState } from 'react'
import PropertyImage from './PropertyImage'

type Property = {
  id: number
  title: string
  city: string
  pricePerMonth: number
  areaSqm: number
}

export default function PropertyList() {
  const [properties, setProperties] = useState<Property[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true

    async function loadProperties() {
      try {
        const response = await fetch('/api/properties')

        if (!response.ok) {
          throw new Error(`Ошибка API: ${response.status}`)
        }

        const data: Property[] = await response.json()

        if (active) {
          setProperties(data)
        }
      } catch {
        if (active) {
          setError('Не удалось загрузить объявления')
        }
      } finally {
        if (active) {
          setLoading(false)
        }
      }
    }

    loadProperties()

    return () => {
      active = false
    }
  }, [])

  if (loading) return <p>Загрузка объявлений...</p>
  if (error) return <p role="alert">{error}</p>
  if (properties.length === 0) return <p>Объявлений пока нет.</p>

  return (
    <section>
      <h2>Объявления недвижимости</h2>

      <ul>
        {properties.map((property) => (
          <li key={property.id}>
            <PropertyImage propertyId={property.id} />
            <h3>{property.title}</h3>
            <p>Город: {property.city}</p>
            <p>Площадь: {property.areaSqm} м²</p>
            <p>
              Цена: {property.pricePerMonth.toLocaleString('ru-RU')} ₸/мес.
            </p>
          </li>
        ))}
      </ul>
    </section>
  )
}