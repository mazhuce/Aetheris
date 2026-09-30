interface BrandMarkProps {
  className?: string
  decorative?: boolean
}

export default function BrandMark({ className = 'brand-mark', decorative = true }: BrandMarkProps) {
  return (
    <img
      className={className}
      src="/favicon.svg?v=20260930"
      alt={decorative ? '' : 'Aetheris'}
      aria-hidden={decorative || undefined}
    />
  )
}
