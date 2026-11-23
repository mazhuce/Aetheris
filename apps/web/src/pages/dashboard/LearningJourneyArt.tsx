/** Notion 风格黄色线稿插图：学习旅程（内联 SVG，随主题取色）。 */
export default function LearningJourneyArt() {
  return (
    <svg viewBox="0 0 640 360" role="presentation" aria-hidden="true" focusable="false">
      <g fill="none" stroke="var(--illustration-accent, #d9a441)" strokeWidth="3.6" strokeLinecap="round" strokeLinejoin="round">
        <path d="M44 318 L52 266 L164 272 L170 222 L282 228 L288 178 L400 184 L406 132 L518 138 L524 88" />
        <path opacity="0.4" d="M40 316 L48 262 L160 270 L166 218 L278 226 L284 174 L396 182 L402 128 L514 136 L520 84" />
        <path d="M524 88 L524 36" />
        <path d="M524 40 L574 52 L524 66 Z" fill="var(--illustration-accent, #d9a441)" fillOpacity="0.18" />
        <path d="M252 66 q-10 8 0 16 M268 66 q10 8 0 16" />
        <path d="M150 120 l6 6 M156 120 l-6 6 M352 98 l6 6 M358 98 l-6 6 M486 272 l6 6 M492 272 l-6 6" />
      </g>

      <g fill="none" stroke="var(--text-secondary, #9aa0aa)" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round"
        transform="translate(436 110) scale(1.22) translate(-436 -110)">
        <circle cx="436" cy="84" r="11" />
        <path d="M436 95 L432 124 M433 104 L462 92 M432 108 L414 118 M432 124 L420 146 M432 124 L444 146" />
        <path d="M426 76 q10 -6 20 0" />
      </g>
      <g fill="none" stroke="var(--text-secondary, #9aa0aa)" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round">
        <path d="M60 62 h36 v44 h-36 z M64 56 h36 v44 M66 72 h22 M66 80 h22" />
        <circle cx="556" cy="246" r="13" />
        <path d="M548 257 l-5 16 9 -5 4 8 4 -8 9 5 -5 -16" />
      </g>

      <g fill="var(--text-secondary, #9aa0aa)" style={{ fontFamily: 'var(--font-sans)', fontSize: 18 }}>
        <text x="74" y="300" transform="rotate(-1 74 300)">学习路径</text>
        <text x="192" y="254" transform="rotate(-1 192 254)">动手任务</text>
        <text x="310" y="208" transform="rotate(-1 310 208)">结业凭证</text>
        <text x="428" y="162" transform="rotate(-1 428 162)">求职证据</text>
      </g>

      <text x="40" y="42" fill="var(--illustration-accent, #d9a441)"
        style={{ fontFamily: 'var(--font-sans)', fontSize: 20, letterSpacing: '0.08em' }}>
        学习旅程
      </text>
      <path d="M40 52 q40 6 86 0" fill="none" stroke="var(--illustration-accent, #d9a441)"
        strokeWidth="2.4" strokeLinecap="round" opacity="0.7" />
      <path d="M30 330 H610" stroke="var(--border-default, rgba(255,255,255,0.1))"
        strokeWidth="1.6" strokeDasharray="4 8" strokeLinecap="round" />
    </svg>
  )
}