/** @type {import('tailwindcss').Config} */
export default {
  content: [
    './index.html',
    './src/**/*.{vue,js,ts,jsx,tsx}',
  ],
  theme: {
    extend: {
      colors: {
        warm: {
          50: '#fafaf8',
          100: '#f5f4f0',
          200: '#e8e6de',
          300: '#d4d1c7',
          400: '#b0aca0',
          500: '#8c887a',
          600: '#6b675a',
          700: '#4a463c',
          800: '#2d2a23',
          900: '#1a1814',
        },
        accent: {
          50: '#f3f5fb',
          100: '#e4e9f6',
          200: '#c9d3ed',
          300: '#a3b3e0',
          400: '#7d8ed0',
          500: '#5b6ab8',
          600: '#45509e',
          700: '#363f80',
          800: '#2a3164',
          900: '#1c2145',
        },
        amber: {
          50: '#fdfaf3',
          100: '#f9f2e0',
          200: '#f0dfb8',
          300: '#e4c780',
          400: '#d4a94a',
          500: '#bd922e',
          600: '#9c7620',
          700: '#7a5b1b',
          800: '#5c4317',
          900: '#3d2c12',
        },
      },
      fontFamily: {
        sans: ['"Plus Jakarta Sans"', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        display: ['"Playfair Display"', 'Georgia', 'serif'],
        mono: ['"JetBrains Mono"', '"Fira Code"', '"Cascadia Code"', 'monospace'],
      },
      borderRadius: {
        sm: '0.375rem',
        md: '0.625rem',
        lg: '0.875rem',
        xl: '1.25rem',
        '2xl': '1.75rem',
      },
    },
  },
  plugins: [],
}
