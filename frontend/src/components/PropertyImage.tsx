import { useEffect, useState } from 'react'

type ImageInfo = {
  id: number
  propertyId: number
  displayOrder: number
}

type Props = {
  propertyId: number
}

export default function PropertyImage({ propertyId }: Props) {
  const [imageId, setImageId] = useState<number | null>(null)

  useEffect(() => {
    let active = true

    async function loadImages() {
      try {
        const response = await fetch(
          `/api/properties/${propertyId}/images`
        )

        if (!response.ok) {
          throw new Error('Failed to load images')
        }

        const images: ImageInfo[] = await response.json()

        if (active) {
          setImageId(images.length > 0 ? images[0].id : null)
        }
      } catch {
        if (active) {
          setImageId(null)
        }
      }
    }

    loadImages()

    return () => {
      active = false
    }
  }, [propertyId])

  if (imageId === null) {
    return <p>Нет фотографии</p>
  }

  return (
    <img
      src={`/api/properties/${propertyId}/images/${imageId}`}
      alt="Фотография недвижимости"
      loading="lazy"
      style={{
        width: '100%',
        maxWidth: '320px',
        height: '200px',
        objectFit: 'cover',
        borderRadius: '8px',
      }}
    />
  )
}