interface BrandMarkProps {
  className?: string
  decorative?: boolean
}

export default function BrandMark({ className = 'brand-mark', decorative = true }: BrandMarkProps) {
  return (
    <img
      className={className}
      src="/aetheris-mark.svg?v=4"
      alt={decorative ? '' : 'Aetheris'}
      aria-hidden={decorative || undefined}
    />
  )
}
