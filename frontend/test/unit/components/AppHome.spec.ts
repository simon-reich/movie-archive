import { mountSuspended } from '@nuxt/test-utils/runtime'
import { describe, expect, it } from 'vitest'
import AppHome from '~/pages/index.vue'

describe('pages/index.vue', () => {
  it('should render the app heading', async () => {
    const wrapper = await mountSuspended(AppHome)
    expect(wrapper.find('h1').text()).toBe('MovieArchive')
  })

  it('should render the tagline', async () => {
    const wrapper = await mountSuspended(AppHome)
    expect(wrapper.find('p').text()).toBe('Your personal film archive.')
  })
})
