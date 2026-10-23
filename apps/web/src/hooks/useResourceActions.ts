import { useCallback, useEffect, useState, useSyncExternalStore } from 'react'
import { getAuthSnapshot, subscribeAuth } from '@/auth/authState'
import { getMyResourcePermissions, type GovernedResource, type ResourceAction } from '@/api/resourcePermissions'

const actions: ResourceAction[] = ['execute', 'update', 'delete', 'publish', 'query']
interface PermissionState {
  source: readonly { id: number }[]
  subject: ReturnType<typeof getAuthSnapshot>
  type: GovernedResource
  allowed: Partial<Record<ResourceAction, Set<number>>>
}

/** Hints only: every action is still authorized by the resource service. No cross-user or cross-refresh cache. */
export function useResourceActions(type: GovernedResource, resources: readonly { id: number }[]) {
  const subject = useSyncExternalStore(subscribeAuth, getAuthSnapshot, getAuthSnapshot)
  const [state, setState] = useState<PermissionState>()
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    setState(undefined)
    if (subject.status !== 'authenticated') return
    let active = true
    const ids = [...new Set(resources.map((resource) => resource.id).filter((id) => Number.isSafeInteger(id) && id > 0))]
    void Promise.all(actions.map(async (action) => {
      const allowed = new Set<number>()
      try {
        for (let offset = 0; offset < ids.length; offset += 200) {
          const batch = ids.slice(offset, offset + 200)
          const response = await getMyResourcePermissions(type, action, batch)
          if (!active) return [action, new Set<number>()] as const
          for (const id of response) if (batch.includes(id)) allowed.add(id)
        }
      } catch { allowed.clear() }
      return [action, allowed] as const
    })).then((entries) => {
      if (active) setState({ source: resources, subject, type, allowed: Object.fromEntries(entries) })
    })
    return () => { active = false }
  }, [type, resources, subject, revision])
  const current = state?.source === resources && state.subject === subject && state.type === type
  const can = (action: ResourceAction, id: number) => current && state.allowed[action]?.has(id) === true
  const reload = useCallback(() => { setState(undefined); setRevision((value) => value + 1) }, [])
  return { can, loading: subject.status === 'authenticated' && !current, reload }
}
