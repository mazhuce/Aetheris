import js from '@eslint/js'
import tseslint from 'typescript-eslint'
import reactHooks from 'eslint-plugin-react-hooks'
import { fileURLToPath } from 'node:url'

// Deliberately incremental: these are the two R04 asynchronous hooks and their APIs.
export const criticalFiles = [
  'src/hooks/useSerializedPolling.ts',
  'src/pages/ai/chat/useConversationMessages.ts',
  'src/api/ai.ts',
  'src/api/workflow.ts',
]

export default tseslint.config({
  files: criticalFiles,
  extends: [js.configs.recommended, ...tseslint.configs.recommended],
  languageOptions: {
    parserOptions: {
      projectService: true,
      tsconfigRootDir: fileURLToPath(new URL('.', import.meta.url)),
    },
  },
  plugins: { 'react-hooks': reactHooks },
  rules: {
    // React 18 core contracts; no compiler-specific ref/render migration here.
    'react-hooks/rules-of-hooks': 'error',
    'react-hooks/exhaustive-deps': 'error',
    '@typescript-eslint/no-floating-promises': ['error', { ignoreVoid: false }],
    '@typescript-eslint/no-misused-promises': 'error',
  },
})
