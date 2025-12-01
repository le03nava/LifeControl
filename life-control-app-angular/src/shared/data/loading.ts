import { Injectable, signal, computed } from '@angular/core';

/**
 * Global loading state service using signals
 * Tracks loading states across the application
 */
@Injectable({
  providedIn: 'root',
})
export class LoadingService {
  private loadingStates = signal<Set<string>>(new Set());

  /**
   * Computed signal that returns true if any loading state is active
   */
  isLoading = computed(() => this.loadingStates().size > 0);

  /**
   * Computed signal that returns the number of active loading states
   */
  loadingCount = computed(() => this.loadingStates().size);

  /**
   * Start a loading state with a unique key
   */
  startLoading(key: string): void {
    this.loadingStates.update((states) => {
      const newStates = new Set(states);
      newStates.add(key);
      return newStates;
    });
  }

  /**
   * Stop a loading state by key
   */
  stopLoading(key: string): void {
    this.loadingStates.update((states) => {
      const newStates = new Set(states);
      newStates.delete(key);
      return newStates;
    });
  }

  /**
   * Check if a specific loading state is active
   */
  isLoadingKey(key: string): boolean {
    return this.loadingStates().has(key);
  }

  /**
   * Clear all loading states
   */
  clearAll(): void {
    this.loadingStates.set(new Set());
  }
}
