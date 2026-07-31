import * as echarts from 'echarts';
import { useEffect, useRef } from 'react';

export default function EChart({ option }: { option: echarts.EChartsOption }) {
  const target = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!target.current) return;
    const chart = echarts.init(target.current);
    chart.setOption(option);
    const resize = () => chart.resize();
    window.addEventListener('resize', resize);
    return () => {
      window.removeEventListener('resize', resize);
      chart.dispose();
    };
  }, [option]);

  return <div className="echart" ref={target} />;
}
