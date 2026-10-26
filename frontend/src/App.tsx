import { useEffect, useState } from 'react'
import PropertyList from './components/PropertyList'

type PropertyType = {
  id: number
  name: string
}

export default function App() {
  const [propertyTypes, setPropertyTypes] = useState<PropertyType[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true

    async function loadPropertyTypes() {
      try {
        const response = await fetch('/api/property-types')

        if (!response.ok) {
          throw new Error(`Ошибка API: ${response.status}`)
        }

        const data: PropertyType[] = await response.json()

        if (active) {
          setPropertyTypes(data)
        }
      } catch (error) {
        if (active) {
          setError(
            error instanceof Error
              ? error.message
              : 'Не удалось загрузить типы недвижимости',
          )
        }
      } finally {
        if (active) {
          setLoading(false)
        }
      }
    }

    loadPropertyTypes()

    return () => {
      active = false
    }
  }, [])

  return (
    <main>
      <h1>Reservation</h1>
      <h2>Типы недвижимости</h2>

      {loading && <p>Загрузка...</p>}
      {error && <p role="alert">{error}</p>}

      {!loading && !error && (
        <ul>
          {propertyTypes.map((type) => (
            <li key={type.id}>{type.name}</li>
          ))}
        </ul>
      )}

      <PropertyList />
    </main>
  )
}