/**
 * Settings composable — stub created for test scaffolding (plan 02-01).
 * Full implementation in plan 02-03.
 */
export function useSettings() {
  async function saveApiKey(_provider: 'tmdb' | 'omdb', _key: string): Promise<void> {
    await $fetch(`/api/settings/api-keys/${_provider}`, {
      method: 'PUT',
      body: { key: _key },
      credentials: 'include',
    })
  }

  async function loadApiKeys(): Promise<{ tmdb: string | null; omdb: string | null }> {
    return await $fetch('/api/settings/api-keys', {
      credentials: 'include',
    })
  }

  async function changePassword(_currentPassword: string, _newPassword: string): Promise<void> {
    await $fetch('/api/settings/password', {
      method: 'POST',
      body: { currentPassword: _currentPassword, newPassword: _newPassword },
      credentials: 'include',
    })
  }

  async function changeEmail(_newEmail: string): Promise<void> {
    await $fetch('/api/settings/email', {
      method: 'POST',
      body: { newEmail: _newEmail },
      credentials: 'include',
    })
  }

  return { saveApiKey, loadApiKeys, changePassword, changeEmail }
}
