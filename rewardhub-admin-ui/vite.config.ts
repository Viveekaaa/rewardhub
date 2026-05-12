import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    // Output under the Gradle build dir so `./gradlew clean` removes it
    // and so rewardhub-api can bundle it into the boot jar's static resources.
    outDir: 'build/dist',
    emptyOutDir: true,
  },
})
