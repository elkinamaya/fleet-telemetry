import { StatusBar } from 'expo-status-bar';
import * as Location from 'expo-location';
import { openDatabaseAsync } from 'expo-sqlite';
import { useNetworkState } from 'expo-network';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  KeyboardAvoidingView,
  Platform,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { CaptureSourceLabel } from './src/components/CaptureSourceLabel';
import { initializeTelemetryDatabase } from './src/storage/database';
import {
  getSyncSummary,
  listTelemetryEvents,
  saveTelemetryEvent,
} from './src/storage/telemetryStore';
import { createTelemetryEvent } from './src/telemetry/createTelemetryEvent';
import { gpsSpeedMpsToKmh, parseManualSpeedKmh } from './src/telemetry/speed';
import type { CaptureSource, TelemetryRecord } from './src/telemetry/types';
import { createTelemetrySyncService } from './src/sync/telemetrySyncService';
import { theme } from './src/theme';

const databaseName = 'fleet-telemetry.db';
const configuredVehicleId = process.env.EXPO_PUBLIC_VEHICLE_ID?.trim();
const synchronizePendingTelemetry = createTelemetrySyncService();

function getConnectivityLabel(
  isConnected: boolean | null | undefined,
  isInternetReachable: boolean | null | undefined,
): { label: string; tone: 'success' | 'warning' | 'muted' } {
  if (isInternetReachable === true) return { label: 'Internet disponible', tone: 'success' };
  if (isInternetReachable === false || isConnected === false) {
    return { label: 'Sin conexión', tone: 'warning' };
  }
  return { label: 'Conectividad desconocida', tone: 'muted' };
}

function getSyncMessage(status: string, confirmed: number, pending: number, failureCode: string | null) {
  if (status === 'completed' && confirmed === 0) return 'No hay eventos pendientes.';
  if (status === 'completed') {
    return `${confirmed} evento(s) confirmados por Kafka. Esto no confirma su persistencia en PostgreSQL.`;
  }
  if (failureCode === 'SYNC_CONFIGURATION_INVALID') {
    return 'Configura EXPO_PUBLIC_API_BASE_URL y reinicia Expo para sincronizar.';
  }
  if (failureCode === 'NETWORK_ERROR' || failureCode === 'REQUEST_TIMEOUT') {
    return `${confirmed} confirmado(s); ${pending} siguen pendientes. No se recibió confirmación del backend y el resultado podría ser ambiguo.`;
  }
  if (failureCode === 'REQUEST_REJECTED') {
    return `El backend rechazó un lote. ${confirmed} confirmado(s); ${pending} continúan pendientes. Revisa sus datos.`;
  }
  if (failureCode === 'INVALID_BATCH_RESPONSE') {
    return `La respuesta del backend no pudo validarse. ${confirmed} confirmado(s); ${pending} evento(s) siguen pendientes.`;
  }
  if (failureCode === 'PUBLICATION_UNCONFIRMED') {
    return `${confirmed} confirmado(s); ${pending} evento(s) siguen pendientes porque Kafka no confirmó su publicación.`;
  }
  if (status === 'partial') {
    return `${confirmed} confirmado(s); ${pending} continúan pendientes.`;
  }
  if (failureCode?.startsWith('HTTP_')) {
    return `El backend respondió con error (${failureCode.slice(5)}). ${confirmed} confirmado(s); ${pending} siguen pendientes.`;
  }
  return 'No se pudo completar la sincronización. Los eventos no confirmados siguen pendientes.';
}

export default function App() {
  const network = useNetworkState();
  const [vehicleId, setVehicleId] = useState(configuredVehicleId ?? 'TRUCK-001');
  const [latitudeInput, setLatitudeInput] = useState('37.7749');
  const [longitudeInput, setLongitudeInput] = useState('-122.4194');
  const [manualSpeedInput, setManualSpeedInput] = useState('');
  const [database, setDatabase] = useState<Awaited<ReturnType<typeof openDatabaseAsync>>>();
  const [events, setEvents] = useState<TelemetryRecord[]>([]);
  const [pendingCount, setPendingCount] = useState(0);
  const [syncedCount, setSyncedCount] = useState(0);
  const [loading, setLoading] = useState(true);
  const [capturing, setCapturing] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [syncProgress, setSyncProgress] = useState('');
  const [message, setMessage] = useState('');
  const [messageTone, setMessageTone] = useState<'info' | 'warning' | 'error'>('info');

  useEffect(() => {
    let active = true;
    void (async () => {
      try {
        const db = await openDatabaseAsync(databaseName);
        await initializeTelemetryDatabase(db);
        if (active) {
          setDatabase(db);
          await refreshEvents(db);
        }
      } catch {
        if (active) {
          setMessage('No se pudo abrir el almacenamiento local.');
          setMessageTone('error');
        }
      } finally {
        if (active) setLoading(false);
      }
    })();
    return () => {
      active = false;
    };
  }, []);

  async function refreshEvents(db = database) {
    if (!db) return;
    const [localEvents, summary] = await Promise.all([
      listTelemetryEvents(db),
      getSyncSummary(db),
    ]);
    setEvents(localEvents);
    setPendingCount(summary.pending);
    setSyncedCount(summary.synced);
  }

  async function storeCapture(
    latitude: number,
    longitude: number,
    source: CaptureSource,
    speed: number | null = null,
  ) {
    if (!database) return;
    setCapturing(true);
    setMessage('');
    try {
      const event = createTelemetryEvent({
        vehicleId,
        latitude,
        longitude,
        ...(speed === null ? {} : { speed }),
      }, source);
      await saveTelemetryEvent(database, event);
      await refreshEvents();
      setMessage(source === 'manual_simulated'
        ? 'Evento simulado guardado localmente.'
        : 'Ubicación GPS guardada localmente.');
      setMessageTone('info');
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'No se pudo guardar el evento.');
      setMessageTone('error');
    } finally {
      setCapturing(false);
    }
  }

  async function captureGps() {
    setCapturing(true);
    setMessage('');
    try {
      const permission = await Location.requestForegroundPermissionsAsync();
      if (!permission.granted) {
        setMessage('Permiso de ubicación denegado. Puedes usar coordenadas de prueba.');
        setMessageTone('warning');
        return;
      }
      const position = await Location.getCurrentPositionAsync({
        accuracy: Location.Accuracy.Balanced,
      });
      await storeCapture(
        position.coords.latitude,
        position.coords.longitude,
        'gps',
        gpsSpeedMpsToKmh(position.coords.speed),
      );
    } catch {
      setMessage('No se pudo obtener la ubicación GPS.');
      setMessageTone('error');
    } finally {
      setCapturing(false);
    }
  }

  async function captureManual() {
    const latitude = Number(latitudeInput);
    const longitude = Number(longitudeInput);
    if (!latitudeInput.trim() || !longitudeInput.trim()
      || !Number.isFinite(latitude) || !Number.isFinite(longitude)
      || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
      setMessage('Introduce latitud entre -90 y 90 y longitud entre -180 y 180.');
      setMessageTone('warning');
      return;
    }
    let speed: number | null;
    try {
      speed = parseManualSpeedKmh(manualSpeedInput);
    } catch (error) {
      setMessage(error instanceof Error
        ? error.message
        : 'Introduce una velocidad simulada válida, igual o mayor que 0 km/h.');
      setMessageTone('warning');
      return;
    }
    await storeCapture(latitude, longitude, 'manual_simulated', speed);
  }

  async function synchronize() {
    if (!database || syncing || capturing) return;
    setSyncing(true);
    setSyncProgress('Preparando eventos pendientes…');
    setMessage('');
    try {
      const result = await synchronizePendingTelemetry(database, async (progress) => {
        setSyncProgress(`Lote ${progress.completedBatches} de ${progress.totalBatches} · ${progress.confirmed} confirmado(s)`);
        try {
          await refreshEvents();
        } catch {
          setMessage('No se pudo refrescar la lista local durante el envío. El resultado se volverá a cargar desde SQLite.');
          setMessageTone('error');
        }
      });
      try {
        await refreshEvents();
      } catch {
        setMessage('El envío terminó, pero no se pudo refrescar la lista local desde SQLite. Reabre la aplicación para cargar el estado guardado.');
        setMessageTone('error');
        return;
      }
      setMessage(getSyncMessage(
        result.status,
        result.confirmed,
        result.pending,
        result.failureCode,
      ));
      setMessageTone(result.status === 'failed' ? 'error' : result.status === 'partial' ? 'warning' : 'info');
    } catch {
      setMessage('Falló el almacenamiento local al guardar el resultado. Los eventos siguen en el dispositivo; su publicación podría ser ambigua.');
      setMessageTone('error');
      try {
        await refreshEvents();
      } catch {
        setMessage('No se pudo actualizar la vista desde SQLite. Reabre la aplicación para cargar su estado local.');
      }
    } finally {
      setSyncing(false);
      setSyncProgress('');
    }
  }

  const connectivity = getConnectivityLabel(network.isConnected, network.isInternetReachable);

  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.safeArea}>
        <StatusBar style="light" />
        <KeyboardAvoidingView
          style={styles.flex}
          behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
        >
          <FlatList
          contentContainerStyle={styles.content}
          data={events}
          keyExtractor={(item) => item.event.eventId}
          keyboardShouldPersistTaps="handled"
          ListHeaderComponent={(
            <View>
              <View style={styles.topBar}>
                <View style={styles.brandMark} accessibilityLabel="Fleet Telemetry">
                  <View style={[styles.brandBar, styles.brandBarOne]} />
                  <View style={[styles.brandBar, styles.brandBarTwo]} />
                  <View style={[styles.brandBar, styles.brandBarThree]} />
                </View>
                <View style={styles.brandText}>
                  <Text style={styles.brandTitle}>FLEET <Text style={styles.brandLight}>TELEMETRY</Text></Text>
                  <Text style={styles.brandCaption}>DRIVER CONSOLE</Text>
                </View>
                <View style={[styles.connectionPill, styles[`connection_${connectivity.tone}`]]}>
                  <View style={[styles.statusDot, styles[`dot_${connectivity.tone}`]]} />
                  <Text style={styles.connectionText}>{connectivity.label}</Text>
                </View>
              </View>

              <View style={styles.hero}>
                <Text style={styles.eyebrow}>OPERACIÓN LOCAL</Text>
                <Text style={styles.title}>Telemetría del conductor</Text>
                <Text style={styles.subtitle}>Captura ahora. Sincroniza cuando tengas conexión.</Text>
                <Text style={styles.fieldLabel}>VEHÍCULO</Text>
                <TextInput
                  accessibilityLabel="Identificador del vehículo"
                  value={vehicleId}
                  onChangeText={setVehicleId}
                  autoCapitalize="characters"
                  maxLength={100}
                  style={styles.input}
                  placeholder="TRUCK-001"
                  placeholderTextColor={theme.colors.textDisabled}
                  returnKeyType="done"
                />
              </View>

              <View style={styles.metricsRow}>
                <MetricCard label="PENDIENTES" value={pendingCount} accent="teal" />
                <MetricCard label="SINCRONIZADOS" value={syncedCount} accent="blue" />
              </View>

              <View style={styles.panel}>
                <SectionHeading eyebrow="REGISTRO" title="Capturar ubicación" />
                <ActionButton
                  label="Capturar ubicación GPS"
                  onPress={() => void captureGps()}
                  disabled={loading || capturing || syncing || !database}
                  primary
                />
                <Text style={styles.simulatedHeading}>COORDENADAS DE PRUEBA · SIMULADAS</Text>
                <View style={styles.coordinateRow}>
                  <TextInput
                    accessibilityLabel="Latitud simulada"
                    value={latitudeInput}
                    onChangeText={setLatitudeInput}
                    keyboardType="decimal-pad"
                    style={[styles.input, styles.coordinateInput]}
                    placeholder="Latitud"
                    placeholderTextColor={theme.colors.textDisabled}
                  />
                  <TextInput
                    accessibilityLabel="Longitud simulada"
                    value={longitudeInput}
                    onChangeText={setLongitudeInput}
                    keyboardType="decimal-pad"
                    style={[styles.input, styles.coordinateInput]}
                    placeholder="Longitud"
                    placeholderTextColor={theme.colors.textDisabled}
                  />
                </View>
                <Text style={styles.speedLabel}>VELOCIDAD SIMULADA · KM/H · OPCIONAL</Text>
                <TextInput
                  accessibilityLabel="Velocidad simulada en km/h"
                  value={manualSpeedInput}
                  onChangeText={setManualSpeedInput}
                  keyboardType="decimal-pad"
                  style={styles.input}
                  placeholder="Velocidad en km/h"
                  placeholderTextColor={theme.colors.textDisabled}
                />
                <ActionButton
                  label="Guardar coordenadas simuladas"
                  onPress={() => void captureManual()}
                  disabled={loading || capturing || syncing || !database}
                />
              </View>

              <View style={styles.syncPanel}>
                <View style={styles.syncCopy}>
                  <Text style={styles.eyebrow}>ENVÍO MANUAL</Text>
                  <Text style={styles.syncTitle}>Sincronización por lotes</Text>
                  <Text style={styles.syncNote}>Los eventos se conservan localmente hasta confirmar su publicación.</Text>
                </View>
                <ActionButton
                  label={syncing ? 'Sincronizando…' : 'Sincronizar ahora'}
                  sublabel={syncing ? syncProgress : undefined}
                  onPress={() => void synchronize()}
                  disabled={loading || syncing || capturing || pendingCount === 0 || !database}
                  primary
                  icon={syncing ? <ActivityIndicator color={theme.colors.darkText} /> : undefined}
                />
              </View>

              {loading && <ActivityIndicator style={styles.loader} color={theme.colors.tealBright} />}
              {capturing && <ActivityIndicator style={styles.loader} color={theme.colors.tealBright} />}
              {syncing && (
                <View style={styles.progressRow} accessibilityLiveRegion="polite">
                  <ActivityIndicator color={theme.colors.tealBright} />
                  <Text style={styles.progressText}>{syncProgress}</Text>
                </View>
              )}
              {!!message && (
                <Text
                  accessibilityLiveRegion="polite"
                  style={[styles.message, styles[`message_${messageTone}`]]}
                >
                  {message}
                </Text>
              )}

              <View style={styles.listHeading}>
                <SectionHeading eyebrow="ALMACENAMIENTO DEL DISPOSITIVO" title="Eventos locales" />
                <Text style={styles.eventCount}>{events.length}</Text>
              </View>
            </View>
          )}
          ListEmptyComponent={!loading ? (
            <View style={styles.emptyCard}>
              <Text style={styles.emptyTitle}>Aún no hay eventos</Text>
              <Text style={styles.emptyText}>Captura una ubicación para guardarla en este dispositivo.</Text>
            </View>
          ) : null}
          renderItem={({ item }) => <TelemetryEventRow record={item} />}
          />
        </KeyboardAvoidingView>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

function SectionHeading({ eyebrow, title }: { eyebrow: string; title: string }) {
  return (
    <View style={styles.sectionHeading}>
      <Text style={styles.eyebrow}>{eyebrow}</Text>
      <Text style={styles.sectionTitle}>{title}</Text>
    </View>
  );
}

function MetricCard({
  label,
  value,
  accent,
}: {
  label: string;
  value: number;
  accent: 'teal' | 'blue';
}) {
  return (
    <View style={styles.metricCard}>
      <View style={[styles.metricAccent, accent === 'blue' && styles.metricAccentBlue]} />
      <Text style={styles.metricLabel}>{label}</Text>
      <Text style={styles.metricValue}>{value}</Text>
    </View>
  );
}

function ActionButton({
  label,
  sublabel,
  onPress,
  disabled,
  primary = false,
  icon,
}: {
  label: string;
  sublabel?: string;
  onPress: () => void;
  disabled: boolean;
  primary?: boolean;
  icon?: React.ReactNode;
}) {
  return (
    <TouchableOpacity
      accessibilityRole="button"
      accessibilityState={{ disabled, busy: label === 'Sincronizando…' }}
      onPress={onPress}
      disabled={disabled}
      style={[styles.button, primary && styles.primaryButton, disabled && styles.disabledButton]}
    >
      {icon}
      <View style={styles.buttonCopy}>
        <Text style={[styles.buttonText, primary && styles.primaryButtonText]}>{label}</Text>
        {!!sublabel && <Text style={styles.buttonSublabel}>{sublabel}</Text>}
      </View>
    </TouchableOpacity>
  );
}

function TelemetryEventRow({ record }: { record: TelemetryRecord }) {
  const synced = record.status === 'synced';
  return (
    <View style={styles.eventCard}>
      <View style={styles.eventTopline}>
        <CaptureSourceLabel source={record.event.source} />
        <View style={[styles.statePill, synced ? styles.stateSynced : styles.statePending]}>
          <Text style={[styles.statePillText, synced ? styles.stateSyncedText : styles.statePendingText]}>
            {synced ? 'PUBLISHED · KAFKA' : 'PENDIENTE'}
          </Text>
        </View>
      </View>
      <Text style={styles.eventVehicle}>{record.event.vehicleId}</Text>
      <Text style={styles.eventMeta}>{formatEventTimestamp(record.event.timestamp)}</Text>
      <Text style={styles.eventMeta}>{record.event.latitude}, {record.event.longitude}</Text>
      <Text style={styles.eventMeta}>
        {record.event.speed === null ? 'Velocidad no disponible' : `${record.event.speed} km/h`}
      </Text>
      {record.lastError && (
        <Text style={styles.eventError}>Último intento: {formatSafeError(record.lastError)}</Text>
      )}
    </View>
  );
}

function formatEventTimestamp(timestamp: string): string {
  const parsed = new Date(timestamp);
  if (Number.isNaN(parsed.getTime())) return timestamp;
  return `${parsed.toLocaleString()} · hora local`;
}

function formatSafeError(errorCode: string): string {
  const messages: Record<string, string> = {
    PUBLICATION_UNCONFIRMED: 'publicación no confirmada',
    NETWORK_ERROR: 'sin conexión con backend',
    REQUEST_TIMEOUT: 'tiempo de espera agotado',
    INVALID_BATCH_RESPONSE: 'respuesta no válida',
    SYNC_CONFIGURATION_INVALID: 'configuración incompleta',
    REQUEST_REJECTED: 'lote rechazado',
  };
  return messages[errorCode] ?? 'fallo de sincronización';
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  safeArea: { flex: 1, backgroundColor: theme.colors.background },
  content: { paddingHorizontal: theme.spacing.md, paddingBottom: 42 },
  topBar: {
    alignItems: 'center',
    borderBottomColor: theme.colors.borderSubtle,
    borderBottomWidth: 1,
    flexDirection: 'row',
    gap: 10,
    minHeight: 70,
  },
  brandMark: {
    alignItems: 'flex-end',
    backgroundColor: theme.colors.blueDeep,
    borderColor: '#35d6cb52',
    borderRadius: 11,
    borderWidth: 1,
    flexDirection: 'row',
    gap: 3,
    height: 38,
    justifyContent: 'center',
    padding: 8,
    width: 38,
  },
  brandBar: { backgroundColor: theme.colors.tealBright, borderRadius: 2, flex: 1 },
  brandBarOne: { height: 9, opacity: 0.64 },
  brandBarTwo: { height: 14, opacity: 0.82 },
  brandBarThree: { height: 19 },
  brandText: { flex: 1 },
  brandTitle: { color: theme.colors.textPrimary, fontSize: 13, fontWeight: '800', letterSpacing: 0.4 },
  brandLight: { color: theme.colors.textSecondary, fontWeight: '500' },
  brandCaption: { color: theme.colors.textMuted, fontSize: 9, fontWeight: '700', letterSpacing: 1.3, marginTop: 3 },
  connectionPill: {
    alignItems: 'center',
    borderRadius: 999,
    flexDirection: 'row',
    gap: 6,
    maxWidth: 148,
    paddingHorizontal: 9,
    paddingVertical: 8,
  },
  connection_success: { backgroundColor: '#42c98a14', borderColor: '#42c98a3b', borderWidth: 1 },
  connection_warning: { backgroundColor: '#f2b84b14', borderColor: '#f2b84b3b', borderWidth: 1 },
  connection_muted: { backgroundColor: theme.colors.card, borderColor: theme.colors.border, borderWidth: 1 },
  statusDot: { borderRadius: 5, height: 7, width: 7 },
  dot_success: { backgroundColor: theme.colors.success },
  dot_warning: { backgroundColor: theme.colors.warning },
  dot_muted: { backgroundColor: theme.colors.textMuted },
  connectionText: { color: theme.colors.textSecondary, flexShrink: 1, fontSize: 10, fontWeight: '600' },
  hero: { paddingTop: 25, paddingBottom: 18 },
  eyebrow: { color: theme.colors.tealBright, fontSize: 10, fontWeight: '700', letterSpacing: 1.3 },
  title: { color: theme.colors.textPrimary, fontSize: 25, fontWeight: '700', letterSpacing: -0.5, marginTop: 7 },
  subtitle: { color: theme.colors.textMuted, fontSize: 13, lineHeight: 19, marginTop: 5 },
  fieldLabel: { color: theme.colors.textMuted, fontSize: 10, fontWeight: '700', letterSpacing: 1, marginTop: 19, marginBottom: 7 },
  input: {
    backgroundColor: theme.colors.navigation,
    borderColor: theme.colors.border,
    borderRadius: theme.radius.small,
    borderWidth: 1,
    color: theme.colors.textPrimary,
    fontSize: 15,
    minHeight: 46,
    paddingHorizontal: 13,
  },
  metricsRow: { flexDirection: 'row', gap: 11, marginBottom: 14 },
  metricCard: {
    backgroundColor: theme.colors.card,
    borderColor: theme.colors.borderSubtle,
    borderRadius: theme.radius.medium,
    borderWidth: 1,
    flex: 1,
    minHeight: 92,
    overflow: 'hidden',
    padding: 14,
  },
  metricAccent: { backgroundColor: theme.colors.tealBright, borderRadius: 2, height: 3, marginBottom: 10, width: 25 },
  metricAccentBlue: { backgroundColor: theme.colors.info },
  metricLabel: { color: theme.colors.textMuted, fontSize: 9, fontWeight: '700', letterSpacing: 0.8 },
  metricValue: { color: theme.colors.textPrimary, fontSize: 26, fontWeight: '700', marginTop: 3 },
  panel: {
    backgroundColor: theme.colors.panel,
    borderColor: theme.colors.borderSubtle,
    borderRadius: theme.radius.large,
    borderWidth: 1,
    marginBottom: 13,
    padding: theme.spacing.md,
  },
  sectionHeading: { marginBottom: 13 },
  sectionTitle: { color: theme.colors.textPrimary, fontSize: 18, fontWeight: '600', marginTop: 4 },
  button: {
    alignItems: 'center',
    backgroundColor: theme.colors.elevated,
    borderColor: theme.colors.border,
    borderRadius: theme.radius.small,
    borderWidth: 1,
    flexDirection: 'row',
    justifyContent: 'center',
    minHeight: 48,
    paddingHorizontal: 13,
  },
  primaryButton: { backgroundColor: theme.colors.tealBright, borderColor: theme.colors.tealBright },
  disabledButton: { opacity: 0.48 },
  buttonCopy: { alignItems: 'center' },
  buttonText: { color: theme.colors.textPrimary, fontSize: 14, fontWeight: '700' },
  primaryButtonText: { color: theme.colors.darkText },
  buttonSublabel: { color: theme.colors.textMuted, fontSize: 10, marginTop: 2 },
  simulatedHeading: { color: theme.colors.warning, fontSize: 10, fontWeight: '700', letterSpacing: 0.6, marginTop: 18, marginBottom: 8 },
  speedLabel: { color: theme.colors.warning, fontSize: 10, fontWeight: '700', letterSpacing: 0.6, marginTop: 14, marginBottom: 8 },
  coordinateRow: { flexDirection: 'row', gap: 9, marginBottom: 10 },
  coordinateInput: { flex: 1, fontSize: 13, minWidth: 0, paddingHorizontal: 9 },
  syncPanel: {
    backgroundColor: theme.colors.blueDeep,
    borderColor: '#4b9ddb45',
    borderRadius: theme.radius.large,
    borderWidth: 1,
    marginBottom: 13,
    padding: theme.spacing.md,
  },
  syncCopy: { marginBottom: 14 },
  syncTitle: { color: theme.colors.textPrimary, fontSize: 17, fontWeight: '700', marginTop: 5 },
  syncNote: { color: theme.colors.textSecondary, fontSize: 12, lineHeight: 17, marginTop: 5 },
  loader: { marginVertical: 9 },
  progressRow: { alignItems: 'center', flexDirection: 'row', gap: 10, marginVertical: 11 },
  progressText: { color: theme.colors.textSecondary, flex: 1, fontSize: 12 },
  message: {
    borderRadius: theme.radius.small,
    borderWidth: 1,
    fontSize: 12,
    lineHeight: 18,
    marginBottom: 13,
    padding: 12,
  },
  message_info: { backgroundColor: '#64b5f610', borderColor: '#64b5f633', color: theme.colors.textSecondary },
  message_warning: { backgroundColor: '#f2b84b12', borderColor: '#f2b84b40', color: theme.colors.warning },
  message_error: { backgroundColor: '#f16b7212', borderColor: '#f16b7240', color: theme.colors.error },
  listHeading: { alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between', marginTop: 9 },
  eventCount: {
    backgroundColor: theme.colors.card,
    borderColor: theme.colors.border,
    borderRadius: 999,
    borderWidth: 1,
    color: theme.colors.textSecondary,
    fontSize: 12,
    fontWeight: '700',
    overflow: 'hidden',
    paddingHorizontal: 10,
    paddingVertical: 5,
  },
  eventCard: {
    backgroundColor: theme.colors.card,
    borderColor: theme.colors.borderSubtle,
    borderRadius: theme.radius.medium,
    borderWidth: 1,
    marginBottom: 9,
    padding: 13,
  },
  eventTopline: { alignItems: 'center', flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'space-between', rowGap: 8 },
  statePill: { borderRadius: 999, borderWidth: 1, paddingHorizontal: 8, paddingVertical: 5 },
  stateSynced: { backgroundColor: '#42c98a14', borderColor: '#42c98a3b' },
  statePending: { backgroundColor: '#f2b84b12', borderColor: '#f2b84b3b' },
  statePillText: { fontSize: 8, fontWeight: '800', letterSpacing: 0.5 },
  stateSyncedText: { color: theme.colors.success },
  statePendingText: { color: theme.colors.warning },
  eventVehicle: { color: theme.colors.textPrimary, fontSize: 15, fontWeight: '700', marginTop: 10 },
  eventMeta: { color: theme.colors.textMuted, fontSize: 11, marginTop: 5 },
  eventError: { color: theme.colors.warning, fontSize: 11, marginTop: 8 },
  emptyCard: {
    backgroundColor: theme.colors.panel,
    borderColor: theme.colors.borderSubtle,
    borderRadius: theme.radius.medium,
    borderWidth: 1,
    padding: 16,
  },
  emptyTitle: { color: theme.colors.textSecondary, fontSize: 14, fontWeight: '700' },
  emptyText: { color: theme.colors.textMuted, fontSize: 12, lineHeight: 18, marginTop: 4 },
});
