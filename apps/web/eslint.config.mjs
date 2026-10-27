import js from '@eslint/js'
import tseslint from 'typescript-eslint'
import reactHooks from 'eslint-plugin-react-hooks'
import { fileURLToPath } from 'node:url'

// Incremental coverage of asynchronous hooks, session boundaries and acceptance evidence.
export const criticalFiles = [
  'src/hooks/useSerializedPolling.ts',
  'src/pages/ai/chat/useConversationMessages.ts',
  'src/api/ai.ts',
  'src/api/workflow.ts',
  'src/api/http.ts',
  'src/api/auth.ts',
  'src/auth/authState.ts',
  'src/auth/browserSession.ts',
  'src/auth/queryClient.ts',
  'src/auth/refreshCoordinator.ts',
  'src/auth/session.ts',
  'src/auth/sessionSynchronization.ts',
  'src/components/RequireAuth.tsx',
  'src/pages/login/index.tsx',
  'src/pages/assets/tables/index.tsx',
  'src/pages/assets/tables/useTableDetail.ts',
  'src/pages/development/workflows/index.tsx',
  'src/pages/development/workflows/dependencyRequests.ts',
  'src/pages/operations/tasks/index.tsx',
  'src/pages/operations/tasks/taskStatistics.ts',
  'src/pages/development/releases/index.tsx',
  'src/pages/analytics/dashboards/components/DashboardWidgetContent.tsx',
  'src/api/acceptance.ts',
  'src/api/acceptanceContract.ts',
  'src/pages/development/acceptance/index.tsx',
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
