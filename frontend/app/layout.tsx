import type { Metadata } from 'next';
import '@xyflow/react/dist/style.css';
import './studio.css';

export const metadata: Metadata = {
  title: 'Database Performance Studio',
  description: 'A local PostgreSQL performance engineering workspace.',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return <html lang="en"><body>{children}</body></html>;
}
