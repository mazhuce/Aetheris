interface BrandMarkProps {
  className?: string
  decorative?: boolean
}

export default function BrandMark({ className = 'brand-mark', decorative = true }: BrandMarkProps) {
  return (
    <img
      className={className}
      src="/aetheris-mark-v5.svg"
      alt={decorative ? '' : 'Aetheris'}
      aria-hidden={decorative || undefined}
    />
  )
}
