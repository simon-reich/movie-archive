import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import TrailerEmbed from '@/components/TrailerEmbed.vue'

const TRAILER_KEY = 'YoHD9XEInc0'

describe('TrailerEmbed component', () => {
  it('renders YouTube thumbnail when trailerKey is provided', () => {
    const wrapper = mount(TrailerEmbed, { props: { trailerKey: TRAILER_KEY } })
    const img = wrapper.find('img')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toContain(`https://img.youtube.com/vi/${TRAILER_KEY}/hqdefault.jpg`)
  })

  it('does not render trailer section when trailerKey is null', () => {
    const wrapper = mount(TrailerEmbed, { props: { trailerKey: null } })
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('iframe').exists()).toBe(false)
  })

  it('shows play button overlay before click', () => {
    const wrapper = mount(TrailerEmbed, { props: { trailerKey: TRAILER_KEY } })
    // iframe should NOT exist before click
    expect(wrapper.find('iframe').exists()).toBe(false)
    // play overlay div should exist
    expect(wrapper.find('.bg-primary').exists()).toBe(true)
  })

  it('replaces thumbnail with iframe after click', async () => {
    const wrapper = mount(TrailerEmbed, { props: { trailerKey: TRAILER_KEY } })
    await wrapper.find('.relative.cursor-pointer').trigger('click')
    expect(wrapper.find('iframe').exists()).toBe(true)
    expect(wrapper.find('img').exists()).toBe(false)
  })

  it('iframe src uses correct YouTube embed URL with autoplay', async () => {
    const wrapper = mount(TrailerEmbed, { props: { trailerKey: TRAILER_KEY } })
    await wrapper.find('.relative.cursor-pointer').trigger('click')
    const iframe = wrapper.find('iframe')
    expect(iframe.attributes('src')).toBe(
      `https://www.youtube.com/embed/${TRAILER_KEY}?autoplay=1`
    )
  })
})
