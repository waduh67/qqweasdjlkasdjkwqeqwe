/** Compare physical identity without rewriting recorded serials or frozen command payloads. */
export function sameSerialIdentity(observed: string | null | undefined, recorded: string | null | undefined) {
  const candidate = observed?.trim().toUpperCase()
  return !!candidate && candidate === recorded?.trim().toUpperCase()
}
