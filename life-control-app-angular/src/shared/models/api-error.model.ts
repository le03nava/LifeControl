export interface ApiError {
  status: number;
  message: string;
  errors?: Record<string, string>;
  path: string;
  timestamp: string;
  correlationId: string;
}
