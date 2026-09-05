export interface SubmissionAttempt {
  key: string
  finish(success: boolean): void
}

export class LatestRequestGate {
  private version = 0

  next(): number {
    this.version += 1
    return this.version
  }

  isLatest(version: number): boolean {
    return version === this.version
  }

  invalidate(): void {
    this.version += 1
  }
}

export class SubmissionCoordinator {
  private readonly active = new Set<string>()
  private readonly attempts = new Map<string, { key: string; fingerprint: string }>()

  constructor(private readonly createKey: () => string = () => `web-${crypto.randomUUID()}`) {}

  begin(operation: string, fingerprint = operation): SubmissionAttempt | null {
    if (this.active.has(operation)) return null
    this.active.add(operation)
    const previous = this.attempts.get(operation)
    const key = previous?.fingerprint === fingerprint ? previous.key : this.createKey()
    this.attempts.set(operation, { key, fingerprint })
    return {
      key,
      finish: (success: boolean) => {
        this.active.delete(operation)
        if (success) this.attempts.delete(operation)
      },
    }
  }

  reset(operation: string): void {
    this.active.delete(operation)
    this.attempts.delete(operation)
  }
}
