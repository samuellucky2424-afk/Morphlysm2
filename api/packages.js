import { createServiceClient } from './_shared/supabase.js';
import {
  applyHttpHeaders,
  handleOptions,
  publicError,
  requireMethod,
} from './_shared/http.js';

export default async function handler(req, res) {
  applyHttpHeaders(req, res, 'GET, OPTIONS');
  if (handleOptions(req, res) || !requireMethod(req, res, 'GET')) return;

  try {
    const { data, error } = await createServiceClient()
      .from('credit_package_sm')
      .select('code,name,description,credits,price,currency,time_label,sort_order')
      .eq('is_active', true)
      .order('sort_order', { ascending: true });
    if (error) throw error;
    return res.status(200).json({
      packages: (data || []).map((item) => ({
        id: item.code,
        name: item.name,
        description: item.description || '',
        credits: Number(item.credits),
        price: Number(item.price),
        currency: item.currency,
        timeLabel: item.time_label || '',
      })),
    });
  } catch (error) {
    console.error('Packages API failed:', error);
    return res.status(500).json({ error: publicError(error) });
  }
}
