import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const css = readFileSync(new URL('./DecoracionBotanica.css', `file://${process.cwd()}/src/components/layout/DecoracionBotanica.css`), 'utf8');

describe('DecoracionBotanica CSS aprobado CCPIP-435', () => {
  it('usa el gutter centrado del contenido de 1248px con separación de 24px', () => {
    expect(css).toMatch(/\.decoracion-botanica\s*\{[^}]*inset:\s*172px\s+0\s+0\s*;/s);
    expect(css).toMatch(/width:\s*min\(264px,\s*calc\(\(100vw\s*-\s*1248px\)\s*\/\s*2\s*-\s*24px\)\)\s*;/);
    expect(css).toMatch(/\.decoracion-botanica__rama--izquierda\s*\{[^}]*left:\s*16px\s*;/s);
    expect(css).toMatch(/\.decoracion-botanica__rama--derecha\s*\{[^}]*right:\s*16px\s*;/s);
  });

  it('oculta las ramas hasta 1439px sin declarar aria-hidden como CSS', () => {
    expect(css).toMatch(/@media\s*\(max-width:\s*1439px\)\s*\{[^}]*\.decoracion-botanica\s*\{\s*display:\s*none\s*;/s);
    expect(css).not.toMatch(/\baria-hidden\s*:/);
  });
});
