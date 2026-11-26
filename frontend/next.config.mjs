export default {
  poweredByHeader: false,
  experimental: { cpus: 2 },
  async rewrites() {
    return [{ source: '/api/:path*', destination: 'http://127.0.0.1:8080/api/:path*' }];
  },
};
