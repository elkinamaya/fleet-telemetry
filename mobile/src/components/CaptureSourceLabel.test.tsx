import { render } from '@testing-library/react-native';
import { CaptureSourceLabel } from './CaptureSourceLabel';

describe('CaptureSourceLabel', () => {
  it('identifies manual coordinates as simulated', async () => {
    const { getByText } = await render(<CaptureSourceLabel source="manual_simulated" />);
    expect(getByText('Simulada · coordenadas de prueba')).toBeTruthy();
  });
});
