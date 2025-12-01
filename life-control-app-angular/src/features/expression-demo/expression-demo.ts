import { Component, signal, computed } from '@angular/core';
import { CurrencyPipe, DecimalPipe } from '@angular/common';

/**
 * Component demonstrating new Angular v20 template expression features
 * Features:
 * - Exponential operator (**)
 * - 'in' operator
 * - Untagged template literals
 * - Control flow syntax
 */
@Component({
  selector: 'app-expression-demo',
  imports: [CurrencyPipe, DecimalPipe],
  template: `
    <div class="expression-demo">
      <h2>Angular v20 Expression Features</h2>

      <!-- Exponential operator -->
      <div class="demo-section">
        <h3>Exponential Operator</h3>
        <p>
          {{ baseNumber() }} to the power of {{ exponent() }} = {{ baseNumber() ** exponent() }}
        </p>
        <p>2 to the power of 8 = {{ 2 ** 8 }}</p>
      </div>

      <!-- 'in' operator -->
      <div class="demo-section">
        <h3>'in' Operator</h3>
        <p>Does 'name' exist in person? {{ 'name' in person() }}</p>
        <p>Does 'age' exist in person? {{ 'age' in person() }}</p>
        <p>Does 'email' exist in person? {{ 'email' in person() }}</p>
      </div>

      <!-- Untagged template literals -->
      <div class="demo-section">
        <h3>Template Literals in Bindings</h3>
        <div [class]="\`demo-card \${cardType()} \${isHighlighted() ? 'highlighted' : ''}\`">
          <p>This card uses template literal for dynamic classes</p>
          <p>Current type: {{ cardType() }}</p>
        </div>

        <div
          [style]="
            \`background-color: \${backgroundColor()}; padding: \${padding()}px; border-radius: \${borderRadius()}px\`
          "
        >
          <p>This div uses template literal for dynamic styles</p>
        </div>
      </div>

      <!-- Control flow with complex expressions -->
      <div class="demo-section">
        <h3>Advanced Control Flow</h3>
        @for (item of items(); track item.id; let i = $index) {
          <div [class]="\`item item-\${i + 1} \${item.priority}\`">
            <strong>{{ item.name }}</strong>
            <!-- Using exponential for priority calculation -->
            <span>Priority Score: {{ item.priorityValue ** 2 }}</span>
            <!-- Using 'in' operator for feature checking -->
            @if ('premium' in item) {
              <span class="premium-badge">Premium Feature</span>
            }
          </div>
        } @empty {
          <p>No items to display</p>
        }
      </div>

      <!-- Mathematical calculations with exponential -->
      <div class="demo-section">
        <h3>Complex Mathematical Expressions</h3>
        <p>Compound Interest: {{ principal() * (1 + rate() / 100) ** years() | currency }}</p>
        <p>Area of Circle: {{ 3.14159 * radius() ** 2 | number: '1.2-2' }}</p>
      </div>
    </div>
  `,
  styles: [
    `
      .expression-demo {
        max-width: 800px;
        margin: 0 auto;
        padding: var(--space-lg);
      }

      .demo-section {
        margin-bottom: var(--space-xl);
        padding: var(--space-lg);
        border: 1px solid var(--color-gray-200);
        border-radius: var(--radius-lg);
        background: white;
      }

      .demo-section h3 {
        margin: 0 0 var(--space-md);
        color: var(--color-primary);
        font-size: 1.25rem;
      }

      .demo-card {
        padding: var(--space-md);
        border: 1px solid var(--color-gray-300);
        border-radius: var(--radius-md);
        margin: var(--space-sm) 0;
        transition: all 0.2s ease;
      }

      .demo-card.primary {
        border-color: var(--color-primary);
        background-color: rgb(37 99 235 / 0.1);
      }

      .demo-card.secondary {
        border-color: var(--color-gray-400);
        background-color: var(--color-gray-50);
      }

      .demo-card.highlighted {
        box-shadow: var(--shadow-md);
        transform: translateY(-2px);
      }

      .item {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: var(--space-sm);
        margin: var(--space-sm) 0;
        border-radius: var(--radius-sm);
        background: var(--color-gray-50);
      }

      .item.high {
        background: rgb(239 68 68 / 0.1);
        border-left: 4px solid var(--color-error);
      }

      .item.medium {
        background: rgb(245 158 11 / 0.1);
        border-left: 4px solid var(--color-warning);
      }

      .item.low {
        background: rgb(16 185 129 / 0.1);
        border-left: 4px solid var(--color-success);
      }

      .premium-badge {
        background: gold;
        color: #000;
        padding: var(--space-xs) var(--space-sm);
        border-radius: var(--radius-sm);
        font-size: 0.75rem;
        font-weight: 600;
      }
    `,
  ],
})
export class ExpressionDemo {
  // Signals for demonstrating expressions
  baseNumber = signal(3);
  exponent = signal(4);

  person = signal({
    name: 'John Doe',
    age: 30,
    // Notice 'email' property is missing for 'in' operator demo
  });

  cardType = signal('primary');
  isHighlighted = signal(true);

  // Computed signals for styling
  backgroundColor = computed(() => (this.cardType() === 'primary' ? '#3b82f6' : '#6b7280'));
  padding = computed(() => (this.isHighlighted() ? 20 : 16));
  borderRadius = computed(() => (this.isHighlighted() ? 12 : 8));

  // Data for control flow demonstration
  items = signal([
    {
      id: 1,
      name: 'High Priority Task',
      priority: 'high',
      priorityValue: 3,
      premium: true, // Has premium feature
    },
    {
      id: 2,
      name: 'Medium Priority Task',
      priority: 'medium',
      priorityValue: 2,
      // No premium feature
    },
    {
      id: 3,
      name: 'Low Priority Task',
      priority: 'low',
      priorityValue: 1,
      premium: true,
    },
  ]);

  // Financial calculation signals
  principal = signal(1000);
  rate = signal(5); // 5% interest rate
  years = signal(10);
  radius = signal(5);
}
