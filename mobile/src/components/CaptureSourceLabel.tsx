import { StyleSheet, Text } from 'react-native';
import type { CaptureSource } from '../telemetry/types';
import { theme } from '../theme';

export function CaptureSourceLabel({ source }: { source: CaptureSource }) {
  const simulated = source === 'manual_simulated';
  return (
    <Text style={[styles.label, simulated ? styles.simulated : styles.gps]}>
      {simulated ? 'Simulada · coordenadas de prueba' : 'GPS · ubicación del dispositivo'}
    </Text>
  );
}

const styles = StyleSheet.create({
  label: { fontSize: 14, fontWeight: '700' },
  gps: { color: theme.colors.info },
  simulated: { color: theme.colors.warning },
});
