import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import { ProfileResponse, ProfileUpdateRequest } from './profile.models';

@Injectable({
  providedIn: 'root',
})
export class ProfileService {
  private configService = inject(ConfigService);
  private http = inject(HttpClient);

  private get profileUrl(): string {
    return `${this.configService.apiUrl}/profile`;
  }

  /** Fetch the authenticated user's profile (basic info + location preferences). */
  getProfile(): Observable<ProfileResponse> {
    return this.http.get<ProfileResponse>(this.profileUrl);
  }

  /** Update the authenticated user's profile. All fields are optional. */
  updateProfile(data: ProfileUpdateRequest): Observable<ProfileResponse> {
    return this.http.put<ProfileResponse>(this.profileUrl, data);
  }
}
