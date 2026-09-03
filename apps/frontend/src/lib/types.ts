// 백엔드 게이트웨이가 내보내는 그대로의 모양(snake_case)을 쓴다.
// 변환 계층을 두지 않으므로 필드명을 바꾸지 말 것.

export interface Stream {
  id: number;
  name: string;
  /** WKT LineString. 예: "LINESTRING(127.01 37.52, 127.04 37.55)" */
  location: string;
  created_at: string;
}

export interface Trail {
  id: number;
  stream_id: number;
  camera_number: string;
  /** WKT Point. 예: "POINT(127.02 37.53)" */
  location: string;
  direction: string;
  status: string;
  created_at: string;
}

export interface Capture {
  id: number;
  trail_id: number;
  stream_id: number;
  /** URL이 아니라 객체 키다. imageUrl()로 변환해서 쓸 것. */
  image_path: string;
  road_status: string;
  confidence: number;
  created_at: string;
  updated_at: string;
}
