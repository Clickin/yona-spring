export function yieldToMain(): Promise<void> {
  const scheduler = (globalThis as typeof globalThis & {
    scheduler?: { yield(): Promise<void> };
  }).scheduler;
  if (scheduler?.yield) return scheduler.yield();
  const {promise, resolve} = Promise.withResolvers<void>();
  setTimeout(resolve, 0);
  return promise;
}
