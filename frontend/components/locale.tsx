'use client';

import { createContext, useContext, type ReactNode } from 'react';

const Locale = createContext('en');

const vietnamese: Record<string, string> = {
  'Overview': 'Tổng quan', 'Projects': 'Dự án', 'Appearance': 'Giao diện', 'Theme': 'Chủ đề', 'Language': 'Ngôn ngữ',
  'Name': 'Tên', 'Description': 'Mô tả', 'New project': 'Dự án mới', 'Edit project': 'Chỉnh sửa dự án', 'Save project': 'Lưu dự án',
  'Choose a project': 'Chọn dự án', 'Manage projects': 'Quản lý dự án', 'No projects yet': 'Chưa có dự án', 'Selected': 'Đã chọn',
  'Open': 'Mở', 'Save': 'Lưu', 'Cancel': 'Hủy', 'Confirm': 'Xác nhận', 'Done': 'Xong', 'Delete': 'Xóa', 'Refresh': 'Làm mới',
  'New sandbox': 'Môi trường mới', 'Create sandbox': 'Tạo môi trường', 'New PostgreSQL sandbox': 'Môi trường PostgreSQL mới',
  'Docker sandboxes': 'Môi trường Docker', 'Sandbox': 'Môi trường thử nghiệm', 'Sandbox name': 'Tên môi trường',
  'Select sandbox': 'Chọn môi trường', 'No sandboxes yet': 'Chưa có môi trường', 'Choose a SQL file': 'Chọn tệp SQL',
  'PostgreSQL version': 'Phiên bản PostgreSQL', 'Memory limit (MB)': 'Giới hạn bộ nhớ (MB)', 'CPU limit': 'Giới hạn CPU',
  'Provisioning mode': 'Chế độ khởi tạo', 'Empty database': 'Cơ sở dữ liệu trống', 'Clone schema only': 'Chỉ sao chép lược đồ',
  'Clone schema and data': 'Sao chép lược đồ và dữ liệu', 'Source connection': 'Kết nối nguồn', 'Select source': 'Chọn nguồn',
  'Start': 'Khởi động', 'Stop': 'Dừng', 'Restart': 'Khởi động lại', 'Import SQL': 'Nhập SQL', 'SQL script': 'Tập lệnh SQL',
  'PostgreSQL connections': 'Kết nối PostgreSQL', 'Add connection': 'Thêm kết nối', 'Edit connection': 'Chỉnh sửa kết nối',
  'Add PostgreSQL connection': 'Thêm kết nối PostgreSQL', 'Connection name': 'Tên kết nối', 'Host': 'Máy chủ',
  'Port': 'Cổng', 'Database': 'Cơ sở dữ liệu', 'Username': 'Tên người dùng', 'Password': 'Mật khẩu', 'SSL mode': 'Chế độ SSL',
  'Connect timeout (s)': 'Thời gian chờ kết nối (giây)', 'Save connection': 'Lưu kết nối', 'Test': 'Kiểm tra',
  'No source connections': 'Chưa có kết nối nguồn', 'Database catalog': 'Danh mục cơ sở dữ liệu', 'Save snapshot': 'Lưu ảnh chụp',
  'Exact counts': 'Đếm chính xác', 'Schemas': 'Lược đồ', 'Relationships': 'Mối quan hệ', 'Select a database': 'Chọn cơ sở dữ liệu',
  'Dataset configuration': 'Cấu hình tập dữ liệu', 'Saved configuration': 'Cấu hình đã lưu', 'Configuration name': 'Tên cấu hình',
  'Records per table': 'Số bản ghi mỗi bảng', 'Random seed': 'Giá trị khởi tạo ngẫu nhiên', 'New configuration': 'Cấu hình mới',
  'Save configuration': 'Lưu cấu hình', 'Generate dataset': 'Tạo tập dữ liệu', 'Generation history': 'Lịch sử tạo dữ liệu',
  'No generation jobs': 'Chưa có tác vụ tạo dữ liệu', 'Saved queries': 'Truy vấn đã lưu', 'Saved SQL queries': 'Truy vấn SQL đã lưu',
  'Run selected queries': 'Chạy truy vấn đã chọn', 'SQL editor': 'Trình soạn thảo SQL', 'Format': 'Định dạng', 'Execute': 'Thực thi',
  'Run': 'Chạy', 'Query name': 'Tên truy vấn', 'Folder': 'Thư mục', 'Timeout (s)': 'Thời gian chờ (giây)',
  'Query results': 'Kết quả truy vấn', 'Commit sandbox writes': 'Lưu thay đổi vào môi trường thử nghiệm',
  'Sandbox indexes': 'Chỉ mục trong môi trường thử nghiệm', 'Build index': 'Tạo chỉ mục', 'Index name': 'Tên chỉ mục', 'Table': 'Bảng',
  'Select table': 'Chọn bảng', 'Index method': 'Phương thức chỉ mục', 'Named configurations': 'Cấu hình có tên',
  'Create index': 'Tạo chỉ mục', 'No indexes to show': 'Chưa có chỉ mục', 'Index configurations': 'Cấu hình chỉ mục',
  'Configure benchmark': 'Cấu hình phép đo hiệu năng', 'Run a PostgreSQL benchmark': 'Đo hiệu năng PostgreSQL', 'Run name': 'Tên lần chạy',
  'Warm-ups': 'Lượt làm nóng', 'Iterations': 'Số lần lặp', 'Concurrency': 'Mức đồng thời', 'Run benchmark': 'Chạy phép đo',
  'Run demo benchmark': 'Chạy phép đo thử', 'No benchmark results yet': 'Chưa có kết quả đo', 'Select run': 'Chọn lần chạy',
  'Choose another run': 'Chọn lần chạy khác', 'Historical comparison': 'So sánh lịch sử', 'Raw timings': 'Thời gian thô',
  'Summary': 'Tóm tắt', 'All dataset sizes': 'Mọi quy mô dữ liệu', 'Back to results': 'Quay lại kết quả', 'Plan A': 'Kế hoạch A',
  'Plan B': 'Kế hoạch B', 'No plan available': 'Chưa có kế hoạch', 'Verify in sandbox': 'Xác minh trong môi trường thử nghiệm',
  'Potential benefit': 'Lợi ích tiềm năng', 'Trade-off': 'Đánh đổi', 'Load configuration': 'Cấu hình tải', 'Load results': 'Kết quả tải',
  'Clients': 'Máy khách', 'Duration (s)': 'Thời lượng (giây)', 'Ramp-up (s)': 'Tăng tải (giây)', 'Start load test': 'Bắt đầu kiểm thử tải',
  'No recent activity': 'Chưa có hoạt động gần đây', 'Recent activity': 'Hoạt động gần đây',
};

export function LocaleProvider({ language, children }: { language: string; children: ReactNode }) {
  return <Locale.Provider value={language}>{children}</Locale.Provider>;
}

export function useTranslate() {
  const lang = useContext(Locale);
  return (value: string) => lang === 'vi' ? vietnamese[value] ?? value : value;
}
