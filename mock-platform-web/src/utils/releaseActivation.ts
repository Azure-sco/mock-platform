export interface ReleaseScope {
  environment: string
  appCode: string
}

export interface ActiveReleaseScope extends ReleaseScope {
  activationVersion: number
}

export function releaseScopeKey(scope: ReleaseScope): string {
  return `${scope.environment}\u0000${scope.appCode}`
}

export function activationVersionFor(
  release: ReleaseScope,
  active: ActiveReleaseScope | null,
): number {
  if (!active) return 0
  if (active.environment !== release.environment || active.appCode !== release.appCode) {
    throw new Error('权威激活状态与目标 Release 的环境或应用不一致')
  }
  return active.activationVersion
}
